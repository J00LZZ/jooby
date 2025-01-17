/**
 * Jooby https://jooby.io
 * Apache License Version 2.0 https://jooby.io/LICENSE.txt
 * Copyright 2014 Edgar Espina
 */
package io.jooby.internal;

import io.jooby.AttachedFile;
import io.jooby.Context;
import io.jooby.ExecutionMode;
import io.jooby.Reified;
import io.jooby.ResponseHandler;
import io.jooby.Route;
import io.jooby.Route.Handler;
import io.jooby.internal.handler.CompletionStageHandler;
import io.jooby.internal.handler.DefaultHandler;
import io.jooby.internal.handler.DetachHandler;
import io.jooby.internal.handler.DispatchHandler;
import io.jooby.internal.handler.KotlinJobHandler;
import io.jooby.internal.handler.SendAttachment;
import io.jooby.internal.handler.SendByteArray;
import io.jooby.internal.handler.SendByteBuf;
import io.jooby.internal.handler.SendByteBuffer;
import io.jooby.internal.handler.SendCharSequence;
import io.jooby.internal.handler.SendDirect;
import io.jooby.internal.handler.SendFileChannel;
import io.jooby.internal.handler.SendStream;
import io.jooby.internal.handler.WorkerHandler;
import io.jooby.internal.handler.reactive.ObservableHandler;
import io.jooby.internal.handler.reactive.ReactivePublisherHandler;
import io.jooby.internal.handler.reactive.ReactorFluxHandler;
import io.jooby.internal.handler.reactive.ReactorMonoHandler;
import io.jooby.internal.handler.reactive.RxFlowableHandler;
import io.jooby.internal.handler.reactive.RxMaybeHandler;
import io.jooby.internal.handler.reactive.RxSingleHandler;
import io.netty.buffer.ByteBuf;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class Pipeline {

  public static Handler compute(ClassLoader loader, Route route, ExecutionMode mode,
      Executor executor, List<ResponseHandler> responseHandler) {
    Type returnType = route.getReturnType();
    Class<?> type = Reified.rawType(returnType);
    if (CompletionStage.class.isAssignableFrom(type)) {
      return completableFuture(mode, route, executor);
    }
    /** Rx 2: */
    // Single:
    Optional<Class> single = loadClass(loader, "io.reactivex.Single");
    if (single.isPresent()) {
      if (single.get().isAssignableFrom(type)) {
        return single(mode, route, executor);
      }
    }
    // Maybe:
    Optional<Class> maybe = loadClass(loader, "io.reactivex.Maybe");
    if (maybe.isPresent()) {
      if (maybe.get().isAssignableFrom(type)) {
        return rxMaybe(mode, route, executor);
      }
    }
    // Flowable:
    Optional<Class> flowable = loadClass(loader, "io.reactivex.Flowable");
    if (flowable.isPresent()) {
      if (flowable.get().isAssignableFrom(type)) {
        return rxFlowable(mode, route, executor);
      }
    }
    // Observable:
    Optional<Class> observable = loadClass(loader, "io.reactivex.Observable");
    if (observable.isPresent()) {
      if (observable.get().isAssignableFrom(type)) {
        return rxObservable(mode, route, executor);
      }
    }
    // Disposable
    Optional<Class> disposable = loadClass(loader, "io.reactivex.disposables.Disposable");
    if (disposable.isPresent()) {
      if (disposable.get().isAssignableFrom(type)) {
        return rxDisposable(mode, route, executor);
      }
    }
    /** Reactor: */
    // Flux:
    Optional<Class> flux = loadClass(loader, "reactor.core.publisher.Flux");
    if (flux.isPresent()) {
      if (flux.get().isAssignableFrom(type)) {
        return reactorFlux(mode, route, executor);
      }
    }
    // Mono:
    Optional<Class> mono = loadClass(loader, "reactor.core.publisher.Mono");
    if (mono.isPresent()) {
      if (mono.get().isAssignableFrom(type)) {
        return reactorMono(mode, route, executor);
      }
    }
    /** Kotlin: */
    Optional<Class> deferred = loadClass(loader, "kotlinx.coroutines.Deferred");
    if (deferred.isPresent()) {
      if (deferred.get().isAssignableFrom(type)) {
        return kotlinJob(mode, route, executor);
      }
    }
    Optional<Class> job = loadClass(loader, "kotlinx.coroutines.Job");
    if (job.isPresent()) {
      if (job.get().isAssignableFrom(type)) {
        return kotlinJob(mode, route, executor);
      }
    }
    Optional<Class> continuation = loadClass(loader, "kotlin.coroutines.Continuation");
    if (continuation.isPresent()) {
      if (continuation.get().isAssignableFrom(type)) {
        return kotlinContinuation(mode, route, executor);
      }
    }

    /** ReactiveStream: */
    Optional<Class> publisher = loadClass(loader, "org.reactivestreams.Publisher");
    if (publisher.isPresent()) {
      if (publisher.get().isAssignableFrom(type)) {
        return reactivePublisher(mode, route, executor);
      }
    }
    /** Context: */
    if (Context.class.isAssignableFrom(type)) {
      if (executor == null && mode == ExecutionMode.EVENT_LOOP) {
        return next(mode, executor, new DetachHandler(route.getPipeline()), false);
      }
      return next(mode, executor, new SendDirect(route.getPipeline()), true);
    }
    /** InputStream: */
    if (InputStream.class.isAssignableFrom(type)) {
      return next(mode, executor, new SendStream(route.getPipeline()), true);
    }
    /** FileChannel: */
    if (FileChannel.class.isAssignableFrom(type) || Path.class.isAssignableFrom(type) || File.class
        .isAssignableFrom(type)) {
      return next(mode, executor, new SendFileChannel(route.getPipeline()), true);
    }
    /** Attached file: */
    if (AttachedFile.class.isAssignableFrom(type)) {
      return next(mode, executor, new SendAttachment(route.getPipeline()), true);
    }
    /** Strings: */
    if (CharSequence.class.isAssignableFrom(type)) {
      return next(mode, executor, new SendCharSequence(route.getPipeline()), true);
    }
    /** RawByte: */
    if (byte[].class == type) {
      return next(mode, executor, new SendByteArray(route.getPipeline()), true);
    }
    if (ByteBuffer.class.isAssignableFrom(type)) {
      return next(mode, executor, new SendByteBuffer(route.getPipeline()), true);
    }
    if (ByteBuf.class.isAssignableFrom(type)) {
      return next(mode, executor, new SendByteBuf(route.getPipeline()), true);
    }

    if (responseHandler != null) {
      return responseHandler.stream().filter(it -> it.matches(returnType))
          .findFirst()
          .map(factory ->
              next(mode, executor, factory.create(route.getPipeline()), true)
          )
          .orElseGet(() -> next(mode, executor, new DefaultHandler(route.getPipeline()), true));
    }
    return next(mode, executor, new DefaultHandler(route.getPipeline()), true);
  }

  private static Handler completableFuture(ExecutionMode mode, Route next, Executor executor) {
    return next(mode, executor, new DetachHandler(new CompletionStageHandler(next.getPipeline())),
        false);
  }

  private static Handler rxFlowable(ExecutionMode mode, Route next, Executor executor) {
    return next(mode, executor, new DetachHandler(new RxFlowableHandler(next.getPipeline())),
        false);
  }

  private static Handler reactivePublisher(ExecutionMode mode, Route next, Executor executor) {
    return next(mode, executor, new DetachHandler(new ReactivePublisherHandler(next.getPipeline())),
        false);
  }

  private static Handler rxDisposable(ExecutionMode mode, Route next, Executor executor) {
    return next(mode, executor, new DetachHandler(new SendDirect(next.getPipeline())),
        false);
  }

  private static Handler rxObservable(ExecutionMode mode, Route next, Executor executor) {
    return next(mode, executor,
        new DetachHandler(new ObservableHandler(next.getPipeline())),
        false);
  }

  private static Handler reactorFlux(ExecutionMode mode, Route next, Executor executor) {
    return next(mode, executor, new DetachHandler(new ReactorFluxHandler(next.getPipeline())),
        false);
  }

  private static Handler reactorMono(ExecutionMode mode, Route next, Executor executor) {
    return next(mode, executor, new DetachHandler(new ReactorMonoHandler(next.getPipeline())),
        false);
  }

  private static Handler kotlinJob(ExecutionMode mode, Route next, Executor executor) {
    return next(mode, executor, new DetachHandler(new KotlinJobHandler(next.getPipeline())),
        false);
  }

  private static Handler kotlinContinuation(ExecutionMode mode, Route next, Executor executor) {
    return next(mode, executor, new DetachHandler(next.getPipeline()), false);
  }

  private static Handler single(ExecutionMode mode, Route next, Executor executor) {
    return next(mode, executor, new DetachHandler(new RxSingleHandler(next.getPipeline())),
        false);
  }

  private static Handler rxMaybe(ExecutionMode mode, Route next, Executor executor) {
    return next(mode, executor, new DetachHandler(new RxMaybeHandler(next.getPipeline())),
        false);
  }

  private static Handler next(ExecutionMode mode, Executor executor, Handler handler,
      boolean blocking) {
    if (executor == null) {
      if (mode == ExecutionMode.WORKER) {
        return new WorkerHandler(handler);
      }
      if (mode == ExecutionMode.DEFAULT && blocking) {
        return new WorkerHandler(handler);
      }
      return handler;
    }
    return new DispatchHandler(handler, executor);
  }

  private static Optional<Class> loadClass(ClassLoader loader, String name) {
    try {
      return Optional.of(loader.loadClass(name));
    } catch (ClassNotFoundException x) {
      return Optional.empty();
    }
  }
}
