/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.webmonitor.handlers;

/*****************************************************************************
 * This code is based on the "HttpStaticFileServerHandler" from the
 * Netty project's HTTP server example.
 *
 * See http://netty.io and
 * https://github.com/netty/netty/blob/4.0/example/src/main/java/io/netty/example/http/file/HttpStaticFileServerHandler.java
 *****************************************************************************/

import org.apache.flink.api.common.time.Time;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.blob.BlobCache;
import org.apache.flink.runtime.blob.BlobKey;
import org.apache.flink.runtime.blob.BlobView;
import org.apache.flink.runtime.concurrent.FlinkFutureException;
import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.runtime.instance.ActorGateway;
import org.apache.flink.runtime.instance.Instance;
import org.apache.flink.runtime.instance.InstanceID;
import org.apache.flink.runtime.messages.JobManagerMessages;
import org.apache.flink.runtime.webmonitor.JobManagerRetriever;
import org.apache.flink.runtime.webmonitor.RuntimeMonitorHandlerBase;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.StringUtils;

import akka.dispatch.Mapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.router.Routed;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import scala.Option;
import scala.concurrent.ExecutionContextExecutor;
import scala.concurrent.duration.FiniteDuration;
import scala.reflect.ClassTag$;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Request handler that returns the TaskManager log/out files.
 *
 * <p>This code is based on the "HttpStaticFileServerHandler" from the Netty project's HTTP server
 * example.</p>
 */
@ChannelHandler.Sharable
public class TaskManagerLogHandler extends RuntimeMonitorHandlerBase {
	private static final Logger LOG = LoggerFactory.getLogger(TaskManagerLogHandler.class);

	private static final String TASKMANAGER_LOG_REST_PATH = "/taskmanagers/:taskmanagerid/log";
	private static final String TASKMANAGER_OUT_REST_PATH = "/taskmanagers/:taskmanagerid/stdout";

	/** Keep track of last transmitted log, to clean up old ones. */
	private final HashMap<String, BlobKey> lastSubmittedLog = new HashMap<>();
	private final HashMap<String, BlobKey> lastSubmittedStdout = new HashMap<>();

	/** Keep track of request status, prevents multiple log requests for a single TM running concurrently. */
	private final ConcurrentHashMap<String, Boolean> lastRequestPending = new ConcurrentHashMap<>();
	private final Configuration config;

	/** Future of the blob cache. */
	private CompletableFuture<BlobCache> cache;

	/** Indicates which log file should be displayed. */
	private FileMode fileMode;

	private final ExecutionContextExecutor executor;

	private final Time timeTimeout;

	private final BlobView blobView;

	/** Used to control whether this handler serves the .log or .out file. */
	public enum FileMode {
		LOG,
		STDOUT
	}

	public TaskManagerLogHandler(
		JobManagerRetriever retriever,
		ExecutionContextExecutor executor,
		scala.concurrent.Future<String> localJobManagerAddressPromise,
		FiniteDuration timeout,
		FileMode fileMode,
		Configuration config,
		boolean httpsEnabled,
		BlobView blobView) {
		super(retriever, localJobManagerAddressPromise, timeout, httpsEnabled);

		this.executor = checkNotNull(executor);
		this.config = config;
		this.fileMode = fileMode;

		this.blobView = Preconditions.checkNotNull(blobView, "blobView");

		timeTimeout = Time.milliseconds(timeout.toMillis());
	}

	@Override
	public String[] getPaths() {
		switch (fileMode) {
			case LOG:
				return new String[]{TASKMANAGER_LOG_REST_PATH};
			case STDOUT:
			default:
				return new String[]{TASKMANAGER_OUT_REST_PATH};
		}
	}

	/**
	 * Response when running with leading JobManager.
	 */
	@Override
	protected void respondAsLeader(final ChannelHandlerContext ctx, final Routed routed, final ActorGateway jobManager) {
		if (cache == null) {
			scala.concurrent.Future<Object> portFuture = jobManager.ask(JobManagerMessages.getRequestBlobManagerPort(), timeout);
			scala.concurrent.Future<BlobCache> cacheFuture = portFuture.map(new Mapper<Object, BlobCache>() {
				@Override
				public BlobCache checkedApply(Object result) throws IOException {
					Option<String> hostOption = jobManager.actor().path().address().host();
					String host = hostOption.isDefined() ? hostOption.get() : "localhost";
					int port = (int) result;
					return new BlobCache(new InetSocketAddress(host, port), config, blobView);
				}
			}, executor);

			cache = FutureUtils.toJava(cacheFuture);
		}

		final String taskManagerID = routed.pathParams().get(TaskManagersHandler.TASK_MANAGER_ID_KEY);
		final HttpRequest request = routed.request();

		//fetch TaskManager logs if no other process is currently doing it
		if (lastRequestPending.putIfAbsent(taskManagerID, true) == null) {
			try {
				InstanceID instanceID = new InstanceID(StringUtils.hexStringToByte(taskManagerID));
				scala.concurrent.Future<JobManagerMessages.TaskManagerInstance> scalaTaskManagerFuture = jobManager
					.ask(new JobManagerMessages.RequestTaskManagerInstance(instanceID), timeout)
					.mapTo(ClassTag$.MODULE$.<JobManagerMessages.TaskManagerInstance>apply(JobManagerMessages.TaskManagerInstance.class));

				CompletableFuture<JobManagerMessages.TaskManagerInstance> taskManagerFuture = FutureUtils.toJava(scalaTaskManagerFuture);

				CompletableFuture<BlobKey> blobKeyFuture = taskManagerFuture.thenCompose(
					taskManagerInstance -> {
						Instance taskManager = taskManagerInstance.instance().get();

						switch (fileMode) {
							case LOG:
								return taskManager.getTaskManagerGateway().requestTaskManagerLog(timeTimeout);
							case STDOUT:
							default:
								return taskManager.getTaskManagerGateway().requestTaskManagerStdout(timeTimeout);
						}
					}
				);

				CompletableFuture<String> logPathFuture = blobKeyFuture
					.thenCombineAsync(
						cache,
						(blobKey, blobCache) -> {
							//delete previous log file, if it is different than the current one
							HashMap<String, BlobKey> lastSubmittedFile = fileMode == FileMode.LOG ? lastSubmittedLog : lastSubmittedStdout;
							if (lastSubmittedFile.containsKey(taskManagerID)) {
								if (!Objects.equals(blobKey, lastSubmittedFile.get(taskManagerID))) {
									try {
										blobCache.deleteGlobal(lastSubmittedFile.get(taskManagerID));
									} catch (IOException e) {
										throw new FlinkFutureException("Could not delete file for " + taskManagerID + '.', e);
									}
									lastSubmittedFile.put(taskManagerID, blobKey);
								}
							} else {
								lastSubmittedFile.put(taskManagerID, blobKey);
							}
							try {
								return blobCache.getURL(blobKey).getFile();
							} catch (IOException e) {
								throw new FlinkFutureException("Could not retrieve blob for " + blobKey + '.', e);
							}
						},
						executor);

				logPathFuture.exceptionally(
					failure -> {
						display(ctx, request, "Fetching TaskManager log failed.");
						LOG.error("Fetching TaskManager log failed.", failure);
						lastRequestPending.remove(taskManagerID);

						return null;
					});

				logPathFuture.thenAccept(
					filePath -> {
						File file = new File(filePath);
						final RandomAccessFile raf;
						try {
							raf = new RandomAccessFile(file, "r");
						} catch (FileNotFoundException e) {
							display(ctx, request, "Displaying TaskManager log failed.");
							LOG.error("Displaying TaskManager log failed.", e);

							return;
						}
						long fileLength;
						try {
							fileLength = raf.length();
						} catch (IOException ioe) {
							display(ctx, request, "Displaying TaskManager log failed.");
							LOG.error("Displaying TaskManager log failed.", ioe);
							try {
								raf.close();
							} catch (IOException e) {
								LOG.error("Could not close random access file.", e);
							}

							return;
						}
						final FileChannel fc = raf.getChannel();

						HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
						response.headers().set(CONTENT_TYPE, "text/plain");

						if (HttpHeaders.isKeepAlive(request)) {
							response.headers().set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
						}
						HttpHeaders.setContentLength(response, fileLength);

						// write the initial line and the header.
						ctx.write(response);

						// write the content.
						ChannelFuture lastContentFuture;
						final GenericFutureListener<Future<? super Void>> completionListener = future -> {
							lastRequestPending.remove(taskManagerID);
							fc.close();
							raf.close();
						};
						if (ctx.pipeline().get(SslHandler.class) == null) {
							ctx.write(
								new DefaultFileRegion(fc, 0, fileLength), ctx.newProgressivePromise())
									.addListener(completionListener);
							lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

						} else {
							try {
								lastContentFuture = ctx.writeAndFlush(
									new HttpChunkedInput(new ChunkedFile(raf, 0, fileLength, 8192)),
									ctx.newProgressivePromise())
									.addListener(completionListener);
							} catch (IOException e) {
								display(ctx, request, "Displaying TaskManager log failed.");
								LOG.warn("Could not write http data.", e);

								return;
							}
							// HttpChunkedInput will write the end marker (LastHttpContent) for us.
						}

						// close the connection, if no keep-alive is needed
						if (!HttpHeaders.isKeepAlive(request)) {
							lastContentFuture.addListener(ChannelFutureListener.CLOSE);
						}
					});
			} catch (Exception e) {
				display(ctx, request, "Error: " + e.getMessage());
				LOG.error("Fetching TaskManager log failed.", e);
				lastRequestPending.remove(taskManagerID);
			}
		} else {
			display(ctx, request, "loading...");
		}
	}

	private void display(ChannelHandlerContext ctx, HttpRequest request, String message) {
		HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
		response.headers().set(CONTENT_TYPE, "text/plain");

		if (HttpHeaders.isKeepAlive(request)) {
			response.headers().set(CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
		}

		byte[] buf = message.getBytes(ConfigConstants.DEFAULT_CHARSET);

		ByteBuf b = Unpooled.copiedBuffer(buf);

		HttpHeaders.setContentLength(response, buf.length);

		// write the initial line and the header.
		ctx.write(response);

		ctx.write(b);

		ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

		// close the connection, if no keep-alive is needed
		if (!HttpHeaders.isKeepAlive(request)) {
			lastContentFuture.addListener(ChannelFutureListener.CLOSE);
		}
	}
}
