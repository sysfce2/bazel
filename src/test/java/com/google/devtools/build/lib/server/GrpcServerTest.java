// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.server;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.CommandDispatcher;
import com.google.devtools.build.lib.runtime.proto.InvocationPolicyOuterClass.InvocationPolicy;
import com.google.devtools.build.lib.server.CommandProtos.CancelRequest;
import com.google.devtools.build.lib.server.CommandProtos.CancelResponse;
import com.google.devtools.build.lib.server.CommandProtos.EnvironmentVariable;
import com.google.devtools.build.lib.server.CommandProtos.RunRequest;
import com.google.devtools.build.lib.server.CommandProtos.RunResponse;
import com.google.devtools.build.lib.server.CommandServerGrpc.CommandServerStub;
import com.google.devtools.build.lib.server.FailureDetails.Command;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.GrpcServer;
import com.google.devtools.build.lib.server.FailureDetails.Interrupted;
import com.google.devtools.build.lib.server.FailureDetails.Interrupted.Code;
import com.google.devtools.build.lib.server.GrpcServerImpl.BlockingStreamObserver;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.io.CommandExtensionReporter;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for the gRPC server. */
@RunWith(JUnit4.class)
public final class GrpcServerTest {

  private static final int SERVER_PID = 42;
  private static final String REQUEST_COOKIE = "request-cookie";

  private final FileSystem fileSystem = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private Server server;
  private ManagedChannel channel;

  private void createServer(CommandDispatcher dispatcher) throws Exception {
    Path serverDirectory = fileSystem.getPath("/bazel_server_directory");
    serverDirectory.createDirectoryAndParents();

    GrpcServerImpl serverImpl =
        new GrpcServerImpl(
            dispatcher,
            ShutdownHooks.createUnregistered(),
            new PidFileWatcher(fileSystem.getPath("/thread-not-running-dont-need"), SERVER_PID),
            new JavaClock(),
            /* port= */ -1,
            REQUEST_COOKIE,
            "response-cookie",
            serverDirectory,
            SERVER_PID,
            /* maxIdleSeconds= */ 1000,
            /* shutdownOnLowSysMem= */ false,
            /* doIdleServerTasks= */ true,
            "slow interrupt message suffix");
    String uniqueName = InProcessServerBuilder.generateName();
    server =
        InProcessServerBuilder.forName(uniqueName)
            .directExecutor()
            .addService(serverImpl)
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(uniqueName).directExecutor().build();
  }

  private static RunRequest createRequest(String... args) {
    return RunRequest.newBuilder()
        .setCookie(REQUEST_COOKIE)
        .setClientDescription("client-description")
        .addAllArg(Arrays.stream(args).map(ByteString::copyFromUtf8).collect(Collectors.toList()))
        .build();
  }

  private static RunRequest createPreemptibleRequest(String... args) {
    return RunRequest.newBuilder()
        .setCookie(REQUEST_COOKIE)
        .setClientDescription("client-description")
        .setPreemptible(true)
        .addAllArg(Arrays.stream(args).map(ByteString::copyFromUtf8).collect(Collectors.toList()))
        .build();
  }

  @Test
  public void testSendingSimpleMessage() throws Exception {
    Any commandExtension = Any.pack(EnvironmentVariable.getDefaultInstance()); // Arbitrary message.
    AtomicReference<List<String>> argsReceived = new AtomicReference<>();
    AtomicReference<List<Any>> commandExtensionsReceived = new AtomicReference<>();
    CommandDispatcher dispatcher =
        new CommandDispatcher() {
          @Override
          public BlazeCommandResult exec(
              InvocationPolicy invocationPolicy,
              List<String> args,
              OutErr outErr,
              LockingMode lockingMode,
              UiVerbosity uiVerbosity,
              String clientDescription,
              long firstContactTimeMillis,
              Optional<List<Pair<String, String>>> startupOptionsTaggedWithBazelRc,
              Supplier<ImmutableList<IdleTask.Result>> idleTaskResultsSupplier,
              List<Any> commandExtensions,
              CommandExtensionReporter commandExtensionReporter) {
            argsReceived.set(args);
            commandExtensionsReceived.set(commandExtensions);
            return BlazeCommandResult.success();
          }
        };
    createServer(dispatcher);

    CountDownLatch done = new CountDownLatch(1);
    CommandServerStub stub = CommandServerGrpc.newStub(channel);
    List<RunResponse> responses = new ArrayList<>();
    stub.run(
        createRequest("Foo").toBuilder().addCommandExtensions(commandExtension).build(),
        createResponseObserver(responses, done));
    done.await();
    server.shutdown();
    server.awaitTermination();

    assertThat(argsReceived.get()).containsExactly("Foo");
    assertThat(commandExtensionsReceived.get()).containsExactly(commandExtension);

    assertThat(responses).hasSize(2);
    assertThat(responses.get(0).getFinished()).isFalse();
    assertThat(responses.get(0).getCookie()).isNotEmpty();
    assertThat(responses.get(1).getFinished()).isTrue();
    assertThat(responses.get(1).getExitCode()).isEqualTo(0);
    assertThat(responses.get(1).hasFailureDetail()).isFalse();
  }

  @Test
  public void testReceiveStreamingCommandExtensions() throws Exception {
    // Arrange: Set up a command that streams back three command extensions, using latches to
    // pause between each extension sent back.
    Any commandExtension1 = Any.pack(Int32Value.of(4));
    Any commandExtension2 = Any.pack(Int32Value.of(8));
    Any commandExtension3 = Any.pack(Int32Value.of(15));

    CountDownLatch afterFirstExtensionLatch = new CountDownLatch(1);
    CountDownLatch beforeSecondExtensionLatch = new CountDownLatch(1);
    CountDownLatch afterSecondExtensionLatch = new CountDownLatch(1);
    CountDownLatch beforeThirdExtensionLatch = new CountDownLatch(1);
    CountDownLatch afterThirdExtensionLatch = new CountDownLatch(1);
    CommandDispatcher dispatcher =
        (policy,
            args,
            outErr,
            lockMode,
            uiVerbosity,
            clientDesc,
            startMs,
            startOpts,
            idleTaskResultsSupplier,
            cmdExts,
            cmdExtOut) -> {
          // Send the first extension.
          cmdExtOut.report(commandExtension1);
          afterFirstExtensionLatch.countDown();
          // Send the second extension.
          beforeSecondExtensionLatch.await(WAIT_TIMEOUT_SECONDS, SECONDS);
          cmdExtOut.report(commandExtension2);
          afterSecondExtensionLatch.countDown();
          // Send the third extension.
          beforeThirdExtensionLatch.await(WAIT_TIMEOUT_SECONDS, SECONDS);
          cmdExtOut.report(commandExtension3);
          afterThirdExtensionLatch.countDown();
          // Finish the fake command.
          return BlazeCommandResult.success();
        };
    createServer(dispatcher);
    CommandServerStub stub = CommandServerGrpc.newStub(channel);

    // Act: Start the streaming RPC.
    List<RunResponse> responses = new ArrayList<>();
    CountDownLatch done = new CountDownLatch(1);
    stub.run(createRequest("Foo"), createResponseObserver(responses, done));

    // Assert: Verify extensions arrive in a streaming fashion.
    // Wait for the first extension and check it.
    afterFirstExtensionLatch.await();
    assertThat(Iterables.getLast(responses).getCommandExtensionsList())
        .containsExactly(commandExtension1);
    beforeSecondExtensionLatch.countDown();
    // Wait for the second extension and check it.
    afterSecondExtensionLatch.await();
    assertThat(Iterables.getLast(responses).getCommandExtensionsList())
        .containsExactly(commandExtension2);
    beforeThirdExtensionLatch.countDown();
    // Wait for the RPC to complete and look for the third extension in the second-to-last response.
    afterThirdExtensionLatch.await();
    done.await();
    assertThat(responses.get(responses.size() - 2).getCommandExtensionsList())
        .containsExactly(commandExtension3);

    // Clean up RPC and server.
    server.shutdown();
    server.awaitTermination();
  }

  @Test
  public void testClosingClientShouldInterrupt() throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    CommandDispatcher dispatcher =
        new CommandDispatcher() {
          @Override
          public BlazeCommandResult exec(
              InvocationPolicy invocationPolicy,
              List<String> args,
              OutErr outErr,
              LockingMode lockingMode,
              UiVerbosity uiVerbosity,
              String clientDescription,
              long firstContactTimeMillis,
              Optional<List<Pair<String, String>>> startupOptionsTaggedWithBazelRc,
              Supplier<ImmutableList<IdleTask.Result>> idleTaskResultsSupplier,
              List<Any> commandExtensions,
              CommandExtensionReporter commandExtensionReporter) {
            synchronized (this) {
              assertThrows(InterruptedException.class, this::wait);
            }
            // The only way this can happen is if the current thread is interrupted.
            done.countDown();
            return BlazeCommandResult.failureDetail(
                FailureDetail.newBuilder()
                    .setInterrupted(Interrupted.newBuilder().setCode(Code.INTERRUPTED_UNKNOWN))
                    .build());
          }
        };
    createServer(dispatcher);

    CommandServerStub stub = CommandServerGrpc.newStub(channel);
    stub.run(
        createRequest("Foo"),
        new StreamObserver<RunResponse>() {
          @Override
          public void onNext(RunResponse value) {
            server.shutdownNow();
            done.countDown();
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });
    server.awaitTermination();
    done.await();
  }

  @Test
  public void testStream() throws Exception {
    CommandDispatcher dispatcher =
        new CommandDispatcher() {
          @Override
          public BlazeCommandResult exec(
              InvocationPolicy invocationPolicy,
              List<String> args,
              OutErr outErr,
              LockingMode lockingMode,
              UiVerbosity uiVerbosity,
              String clientDescription,
              long firstContactTimeMillis,
              Optional<List<Pair<String, String>>> startupOptionsTaggedWithBazelRc,
              Supplier<ImmutableList<IdleTask.Result>> idleTaskResultsSupplier,
              List<Any> commandExtensions,
              CommandExtensionReporter commandExtensionReporter) {
            OutputStream out = outErr.getOutputStream();
            try {
              commandExtensionReporter.report(Any.pack(Int32Value.of(23)));
              for (int i = 0; i < 10; i++) {
                out.write(new byte[1024]);
              }
              commandExtensionReporter.report(Any.pack(Int32Value.of(42)));
            } catch (IOException e) {
              throw new IllegalStateException(e);
            }
            return BlazeCommandResult.withResponseExtensions(
                BlazeCommandResult.success(),
                ImmutableList.of(
                    Any.pack(StringValue.of("foo")),
                    Any.pack(BytesValue.of(ByteString.copyFromUtf8("bar")))));
          }
        };
    createServer(dispatcher);

    CountDownLatch done = new CountDownLatch(1);
    CommandServerStub stub = CommandServerGrpc.newStub(channel);
    List<RunResponse> responses = new ArrayList<>();
    stub.run(createRequest("Foo"), createResponseObserver(responses, done));
    done.await();
    server.shutdown();
    server.awaitTermination();

    assertThat(responses).hasSize(14);
    assertThat(responses.get(0).getFinished()).isFalse();
    assertThat(responses.get(0).getCookie()).isNotEmpty();
    assertThat(responses.get(1).getFinished()).isFalse();
    assertThat(responses.get(1).getCookie()).isNotEmpty();
    assertThat(responses.get(1).getCommandExtensionsList())
        .containsExactly(Any.pack(Int32Value.of(23)));
    for (int i = 2; i < 12; i++) {
      assertThat(responses.get(i).getFinished()).isFalse();
      assertThat(responses.get(i).getStandardOutput().toByteArray()).isEqualTo(new byte[1024]);
      assertThat(responses.get(i).getCommandExtensionsList()).isEmpty();
    }
    assertThat(responses.get(12).getFinished()).isFalse();
    assertThat(responses.get(12).getCookie()).isNotEmpty();
    assertThat(responses.get(12).getCommandExtensionsList())
        .containsExactly(Any.pack(Int32Value.of(42)));
    assertThat(responses.get(13).getFinished()).isTrue();
    assertThat(responses.get(13).getExitCode()).isEqualTo(0);
    assertThat(responses.get(13).hasFailureDetail()).isFalse();
    assertThat(responses.get(13).getCommandExtensionsList())
        .containsExactly(
            Any.pack(StringValue.of("foo")),
            Any.pack(BytesValue.of(ByteString.copyFromUtf8("bar"))));
  }

  @Test
  public void badCookie() throws Exception {
    runBadCommandTest(
        RunRequest.newBuilder().setCookie("bad-cookie").setClientDescription("client-description"),
        FailureDetail.newBuilder()
            .setMessage("Invalid RunRequest: bad cookie")
            .setGrpcServer(GrpcServer.newBuilder().setCode(GrpcServer.Code.BAD_COOKIE))
            .build());
  }

  @Test
  public void emptyClientDescription() throws Exception {
    runBadCommandTest(
        RunRequest.newBuilder().setCookie(REQUEST_COOKIE).setClientDescription(""),
        FailureDetail.newBuilder()
            .setMessage("Invalid RunRequest: no client description")
            .setGrpcServer(GrpcServer.newBuilder().setCode(GrpcServer.Code.NO_CLIENT_DESCRIPTION))
            .build());
  }

  private void runBadCommandTest(RunRequest.Builder runRequestBuilder, FailureDetail failureDetail)
      throws Exception {
    createServer(throwingDispatcher());
    CountDownLatch done = new CountDownLatch(1);
    CommandServerStub stub = CommandServerGrpc.newStub(channel);
    List<RunResponse> responses = new ArrayList<>();

    stub.run(
        runRequestBuilder.addArg(ByteString.copyFromUtf8("Foo")).build(),
        createResponseObserver(responses, done));
    done.await();
    server.shutdown();
    server.awaitTermination();

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0).getFinished()).isTrue();
    assertThat(responses.get(0).getExitCode()).isEqualTo(36);
    assertThat(responses.get(0).hasFailureDetail()).isTrue();
    assertThat(responses.get(0).getFailureDetail()).isEqualTo(failureDetail);
  }

  @Test
  public void unparseableInvocationPolicy() throws Exception {
    createServer(throwingDispatcher());
    CountDownLatch done = new CountDownLatch(1);
    CommandServerStub stub = CommandServerGrpc.newStub(channel);
    List<RunResponse> responses = new ArrayList<>();

    stub.run(
        RunRequest.newBuilder()
            .setCookie(REQUEST_COOKIE)
            .setClientDescription("client-description")
            .setInvocationPolicy("invalid-invocation-policy")
            .addArg(ByteString.copyFromUtf8("Foo"))
            .build(),
        createResponseObserver(responses, done));
    done.await();
    server.shutdown();
    server.awaitTermination();

    assertThat(responses).hasSize(3);
    assertThat(responses.get(2).getFinished()).isTrue();
    assertThat(responses.get(2).getExitCode()).isEqualTo(2);
    assertThat(responses.get(2).hasFailureDetail()).isTrue();
    assertThat(responses.get(2).getFailureDetail())
        .isEqualTo(
            FailureDetail.newBuilder()
                .setMessage(
                    "Invocation policy parsing failed: Malformed value of --invocation_policy: "
                        + "invalid-invocation-policy")
                .setCommand(
                    Command.newBuilder().setCode(Command.Code.INVOCATION_POLICY_PARSE_FAILURE))
                .build());
  }

  @Test
  public void testInterruptStream() throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    CommandDispatcher dispatcher =
        new CommandDispatcher() {
          @Override
          public BlazeCommandResult exec(
              InvocationPolicy invocationPolicy,
              List<String> args,
              OutErr outErr,
              LockingMode lockingMode,
              UiVerbosity uiVerbosity,
              String clientDescription,
              long firstContactTimeMillis,
              Optional<List<Pair<String, String>>> startupOptionsTaggedWithBazelRc,
              Supplier<ImmutableList<IdleTask.Result>> idleTaskResultsSupplier,
              List<Any> commandExtensions,
              CommandExtensionReporter commandExtensionReporter) {
            OutputStream out = outErr.getOutputStream();
            try {
              while (true) {
                if (Thread.interrupted()) {
                  return BlazeCommandResult.failureDetail(
                      FailureDetail.newBuilder()
                          .setInterrupted(
                              Interrupted.newBuilder().setCode(Code.INTERRUPTED_UNKNOWN))
                          .build());
                }
                out.write(new byte[1024]);
              }
            } catch (IOException e) {
              throw new IllegalStateException(e);
            }
          }
        };
    createServer(dispatcher);

    CommandServerStub stub = CommandServerGrpc.newStub(channel);
    List<RunResponse> responses = new ArrayList<>();
    stub.run(
        createRequest("Foo"),
        new StreamObserver<RunResponse>() {
          @Override
          public void onNext(RunResponse value) {
            responses.add(value);
            if (responses.size() == 10) {
              server.shutdownNow();
            }
          }

          @Override
          public void onError(Throwable t) {
            done.countDown();
          }

          @Override
          public void onCompleted() {
            done.countDown();
          }
        });
    server.awaitTermination();
    done.await();
  }

  @Test
  public void testCancel() throws Exception {
    CommandDispatcher dispatcher =
        new CommandDispatcher() {
          @Override
          public BlazeCommandResult exec(
              InvocationPolicy invocationPolicy,
              List<String> args,
              OutErr outErr,
              LockingMode lockingMode,
              UiVerbosity uiVerbosity,
              String clientDescription,
              long firstContactTimeMillis,
              Optional<List<Pair<String, String>>> startupOptionsTaggedWithBazelRc,
              Supplier<ImmutableList<IdleTask.Result>> idleTaskResultsSupplier,
              List<Any> commandExtensions,
              CommandExtensionReporter commandExtensionReporter)
              throws InterruptedException {
            synchronized (this) {
              this.wait();
            }
            // Interruption expected before this is reached.
            throw new IllegalStateException();
          }
        };
    createServer(dispatcher);

    AtomicReference<String> commandId = new AtomicReference<>();
    CountDownLatch gotCommandId = new CountDownLatch(1);
    AtomicReference<RunResponse> secondResponse = new AtomicReference<>();
    CountDownLatch gotSecondResponse = new CountDownLatch(1);
    CommandServerStub stub = CommandServerGrpc.newStub(channel);
    stub.run(
        createRequest("Foo"),
        new StreamObserver<RunResponse>() {
          @Override
          public void onNext(RunResponse value) {
            String previousCommandId = commandId.getAndSet(value.getCommandId());
            if (previousCommandId == null) {
              gotCommandId.countDown();
            } else {
              secondResponse.set(value);
              gotSecondResponse.countDown();
            }
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });
    // Wait until we've got the command id.
    gotCommandId.await();

    CountDownLatch cancelRequestComplete = new CountDownLatch(1);
    CancelRequest cancelRequest =
        CancelRequest.newBuilder().setCookie(REQUEST_COOKIE).setCommandId(commandId.get()).build();
    stub.cancel(
        cancelRequest,
        new StreamObserver<CancelResponse>() {
          @Override
          public void onNext(CancelResponse value) {}

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {
            cancelRequestComplete.countDown();
          }
        });
    cancelRequestComplete.await();
    gotSecondResponse.await();
    server.shutdown();
    server.awaitTermination();

    assertThat(secondResponse.get().getFinished()).isTrue();
    assertThat(secondResponse.get().getExitCode()).isEqualTo(8);
    assertThat(secondResponse.get().hasFailureDetail()).isTrue();
    assertThat(secondResponse.get().getFailureDetail().hasInterrupted()).isTrue();
    assertThat(secondResponse.get().getFailureDetail().getInterrupted().getCode())
        .isEqualTo(Code.INTERRUPTED);
  }

  /**
   * Ensure that if a command is marked as preemptible, running a second command interrupts the
   * first command.
   */
  @Test
  public void testPreeempt() throws Exception {
    String firstCommandArg = "Foo";
    String secondCommandArg = "Bar";

    CommandDispatcher dispatcher =
        new CommandDispatcher() {
          @Override
          public BlazeCommandResult exec(
              InvocationPolicy invocationPolicy,
              List<String> args,
              OutErr outErr,
              LockingMode lockingMode,
              UiVerbosity uiVerbosity,
              String clientDescription,
              long firstContactTimeMillis,
              Optional<List<Pair<String, String>>> startupOptionsTaggedWithBazelRc,
              Supplier<ImmutableList<IdleTask.Result>> idleTaskResultsSupplier,
              List<Any> commandExtensions,
              CommandExtensionReporter commandExtensionReporter) {
            if (args.contains(firstCommandArg)) {
              while (true) {
                try {
                  Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
                } catch (InterruptedException e) {
                  return BlazeCommandResult.failureDetail(
                      FailureDetail.newBuilder()
                          .setInterrupted(Interrupted.newBuilder().setCode(Code.INTERRUPTED))
                          .build());
                }
              }
            } else {
              return BlazeCommandResult.success();
            }
          }
        };
    createServer(dispatcher);

    CountDownLatch gotFoo = new CountDownLatch(1);
    AtomicReference<RunResponse> lastFooResponse = new AtomicReference<>();
    AtomicReference<RunResponse> lastBarResponse = new AtomicReference<>();

    CommandServerStub stub = CommandServerGrpc.newStub(channel);
    stub.run(
        createPreemptibleRequest(firstCommandArg),
        new StreamObserver<RunResponse>() {
          @Override
          public void onNext(RunResponse value) {
            gotFoo.countDown();
            lastFooResponse.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    // Wait for the first command to startup
    gotFoo.await();

    CountDownLatch gotBar = new CountDownLatch(1);
    stub.run(
        createRequest(secondCommandArg),
        new StreamObserver<RunResponse>() {
          @Override
          public void onNext(RunResponse value) {
            gotBar.countDown();
            lastBarResponse.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    gotBar.await();
    server.shutdown();
    server.awaitTermination();

    assertThat(lastBarResponse.get().getFinished()).isTrue();
    assertThat(lastBarResponse.get().getExitCode()).isEqualTo(0);
    assertThat(lastFooResponse.get().getFinished()).isTrue();
    assertThat(lastFooResponse.get().getExitCode()).isEqualTo(8);
    assertThat(lastFooResponse.get().hasFailureDetail()).isTrue();
    assertThat(lastFooResponse.get().getFailureDetail().hasInterrupted()).isTrue();
    assertThat(lastFooResponse.get().getFailureDetail().getInterrupted().getCode())
        .isEqualTo(Code.INTERRUPTED);
  }

  /**
   * Ensure that if a command is marked as preemptible, running a second preemptible command
   * interrupts the first command.
   */
  @Test
  public void testMultiPreeempt() throws Exception {
    String firstCommandArg = "Foo";
    String secondCommandArg = "Bar";

    CommandDispatcher dispatcher =
        new CommandDispatcher() {
          @Override
          public BlazeCommandResult exec(
              InvocationPolicy invocationPolicy,
              List<String> args,
              OutErr outErr,
              LockingMode lockingMode,
              UiVerbosity uiVerbosity,
              String clientDescription,
              long firstContactTimeMillis,
              Optional<List<Pair<String, String>>> startupOptionsTaggedWithBazelRc,
              Supplier<ImmutableList<IdleTask.Result>> idleTaskResultsSupplier,
              List<Any> commandExtensions,
              CommandExtensionReporter commandExtensionReporter)
              throws InterruptedException {
            if (args.contains(firstCommandArg)) {
              while (true) {
                try {
                  Thread.sleep(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
                } catch (InterruptedException e) {
                  return BlazeCommandResult.failureDetail(
                      FailureDetail.newBuilder()
                          .setInterrupted(Interrupted.newBuilder().setCode(Code.INTERRUPTED))
                          .build());
                }
              }
            } else {
              return BlazeCommandResult.success();
            }
          }
        };
    createServer(dispatcher);

    CountDownLatch gotFoo = new CountDownLatch(1);
    AtomicReference<RunResponse> lastFooResponse = new AtomicReference<>();
    AtomicReference<RunResponse> lastBarResponse = new AtomicReference<>();

    CommandServerStub stub = CommandServerGrpc.newStub(channel);
    stub.run(
        createPreemptibleRequest(firstCommandArg),
        new StreamObserver<RunResponse>() {
          @Override
          public void onNext(RunResponse value) {
            gotFoo.countDown();
            lastFooResponse.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    // Wait for the first command to startup
    gotFoo.await();

    CountDownLatch gotBar = new CountDownLatch(1);
    stub.run(
        createPreemptibleRequest(secondCommandArg),
        new StreamObserver<RunResponse>() {
          @Override
          public void onNext(RunResponse value) {
            gotBar.countDown();
            lastBarResponse.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });

    gotBar.await();
    server.shutdown();
    server.awaitTermination();

    assertThat(lastBarResponse.get().getFinished()).isTrue();
    assertThat(lastBarResponse.get().getExitCode()).isEqualTo(0);
    assertThat(lastFooResponse.get().getFinished()).isTrue();
    assertThat(lastFooResponse.get().getExitCode()).isEqualTo(8);
    assertThat(lastFooResponse.get().hasFailureDetail()).isTrue();
    assertThat(lastFooResponse.get().getFailureDetail().hasInterrupted()).isTrue();
    assertThat(lastFooResponse.get().getFailureDetail().getInterrupted().getCode())
        .isEqualTo(Code.INTERRUPTED);
  }

  /**
   * Ensure that when a command is not marked as preemptible, running a second command does not
   * interrupt the first command.
   */
  @Test
  public void testNoPreeempt() throws Exception {
    String firstCommandArg = "Foo";
    String secondCommandArg = "Bar";

    CountDownLatch fooBlocked = new CountDownLatch(1);
    CountDownLatch fooProceed = new CountDownLatch(1);
    CountDownLatch barBlocked = new CountDownLatch(1);
    CountDownLatch barProceed = new CountDownLatch(1);

    CommandDispatcher dispatcher =
        new CommandDispatcher() {
          @Override
          public BlazeCommandResult exec(
              InvocationPolicy invocationPolicy,
              List<String> args,
              OutErr outErr,
              LockingMode lockingMode,
              UiVerbosity uiVerbosity,
              String clientDescription,
              long firstContactTimeMillis,
              Optional<List<Pair<String, String>>> startupOptionsTaggedWithBazelRc,
              Supplier<ImmutableList<IdleTask.Result>> idleTaskResultsSupplier,
              List<Any> commandExtensions,
              CommandExtensionReporter commandExtensionReporter)
              throws InterruptedException {
            if (args.contains(firstCommandArg)) {
              fooBlocked.countDown();
              fooProceed.await();
            } else {
              barBlocked.countDown();
              barProceed.await();
            }
            return BlazeCommandResult.success();
          }
        };
    createServer(dispatcher);

    AtomicReference<RunResponse> lastFooResponse = new AtomicReference<>();
    AtomicReference<RunResponse> lastBarResponse = new AtomicReference<>();

    CommandServerStub stub = CommandServerGrpc.newStub(channel);
    stub.run(
        createRequest(firstCommandArg),
        new StreamObserver<RunResponse>() {
          @Override
          public void onNext(RunResponse value) {
            lastFooResponse.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });
    fooBlocked.await();

    stub.run(
        createRequest(secondCommandArg),
        new StreamObserver<RunResponse>() {
          @Override
          public void onNext(RunResponse value) {
            lastBarResponse.set(value);
          }

          @Override
          public void onError(Throwable t) {}

          @Override
          public void onCompleted() {}
        });
    barBlocked.await();

    // At this point both commands should be blocked on proceed latch, carry on...
    fooProceed.countDown();
    barProceed.countDown();

    server.shutdown();
    server.awaitTermination();

    assertThat(lastFooResponse.get().getFinished()).isTrue();
    assertThat(lastFooResponse.get().getExitCode()).isEqualTo(0);
    assertThat(lastBarResponse.get().getFinished()).isTrue();
    assertThat(lastBarResponse.get().getExitCode()).isEqualTo(0);
  }

  @Test
  public void testFlowControl() throws Exception {
    // This test attempts to verify that FlowControl successfully blocks after some number of onNext
    // calls (however long it takes to fill up gRPCs internal buffers). In order to trigger this
    // behavior, we intentionally block the client after a few successful calls, then wait a bit,
    // and then check that the server has stopped prematurely. Unfortunately, we cannot
    // deterministically verify that the onNext call is blocking. A faulty implementation of
    // FlowControl could pass this test if the sleep is too short. However, a correct implementation
    // should never fail this test.
    // This test could start failing if gRPCs internal buffer size is increased. If it fails after
    // an upgrade of gRPC, you might want to check that.
    CountDownLatch serverDone = new CountDownLatch(1);
    CountDownLatch clientBlocks = new CountDownLatch(1);
    CountDownLatch clientUnblocks = new CountDownLatch(1);
    CountDownLatch clientDone = new CountDownLatch(1);
    AtomicInteger sentCount = new AtomicInteger();
    AtomicInteger receiveCount = new AtomicInteger();
    CommandServerGrpc.CommandServerImplBase serverImpl =
        new CommandServerGrpc.CommandServerImplBase() {
          @Override
          public void run(RunRequest request, StreamObserver<RunResponse> observer) {
            ServerCallStreamObserver<RunResponse> serverCallStreamObserver =
                (ServerCallStreamObserver<RunResponse>) observer;
            BlockingStreamObserver<RunResponse> blockingStreamObserver =
                new BlockingStreamObserver<>(serverCallStreamObserver);
            Thread t =
                new Thread(
                    () -> {
                      RunResponse response =
                          RunResponse.newBuilder()
                              .setStandardOutput(ByteString.copyFrom(new byte[1024]))
                              .build();
                      for (int i = 0; i < 100; i++) {
                        blockingStreamObserver.onNext(response);
                        sentCount.incrementAndGet();
                      }
                      blockingStreamObserver.onCompleted();
                      serverDone.countDown();
                    });
            t.start();
          }
        };

    String uniqueName = InProcessServerBuilder.generateName();
    // Do not use .directExecutor here, as it makes both client and server run in the same thread.
    server =
        InProcessServerBuilder.forName(uniqueName)
            .addService(serverImpl)
            .executor(Executors.newFixedThreadPool(4))
            .build()
            .start();
    channel =
        InProcessChannelBuilder.forName(uniqueName)
            .executor(Executors.newFixedThreadPool(4))
            .build();

    CommandServerStub stub = CommandServerGrpc.newStub(channel);
    stub.run(
        RunRequest.getDefaultInstance(),
        new StreamObserver<RunResponse>() {
          @Override
          public void onNext(RunResponse value) {
            if (sentCount.get() >= 3) {
              clientBlocks.countDown();
              try {
                clientUnblocks.await();
              } catch (InterruptedException e) {
                throw new IllegalStateException(e);
              }
            }
            receiveCount.incrementAndGet();
          }

          @Override
          public void onError(Throwable t) {
            throw new IllegalStateException(t);
          }

          @Override
          public void onCompleted() {
            clientDone.countDown();
          }
        });
    clientBlocks.await();
    // Wait a bit for the server to (hopefully) block. If the server does not block, then this may
    // be flaky.
    Thread.sleep(10);
    assertThat(sentCount.get()).isLessThan(5);
    clientUnblocks.countDown();
    serverDone.await();
    clientDone.await();
    server.shutdown();
    server.awaitTermination();
  }

  @Test
  public void testFlowControlClientCancel() throws Exception {
    // This test attempts to verify that FlowControl unblocks if the client prematurely closes the
    // connection. In that case, FlowControl should observe the onCancel event and interrupt the
    // calling thread. I have observed this test failing with an intentionally introduced bug in
    // FlowControl.
    CountDownLatch serverDone = new CountDownLatch(1);
    CountDownLatch clientDone = new CountDownLatch(1);
    AtomicInteger sentCount = new AtomicInteger();
    AtomicInteger receiveCount = new AtomicInteger();
    CommandServerGrpc.CommandServerImplBase serverImpl =
        new CommandServerGrpc.CommandServerImplBase() {
          @Override
          public void run(RunRequest request, StreamObserver<RunResponse> observer) {
            ServerCallStreamObserver<RunResponse> serverCallStreamObserver =
                (ServerCallStreamObserver<RunResponse>) observer;
            BlockingStreamObserver<RunResponse> blockingStreamObserver =
                new BlockingStreamObserver<>(serverCallStreamObserver);
            Thread t =
                new Thread(
                    () -> {
                      RunResponse response =
                          RunResponse.newBuilder()
                              .setStandardOutput(ByteString.copyFrom(new byte[1024]))
                              .build();
                      for (int i = 0; i < 100; i++) {
                        blockingStreamObserver.onNext(response);
                        sentCount.incrementAndGet();
                      }
                      // FlowControl should have interrupted the current thread after learning of
                      // the server
                      // cancel.
                      assertThat(Thread.currentThread().isInterrupted()).isTrue();
                      blockingStreamObserver.onCompleted();
                      serverDone.countDown();
                    });
            t.start();
          }
        };

    String uniqueName = InProcessServerBuilder.generateName();
    // Do not use .directExecutor here, as it makes both client and server run in the same thread.
    server =
        InProcessServerBuilder.forName(uniqueName)
            .addService(serverImpl)
            .executor(Executors.newFixedThreadPool(4))
            .build()
            .start();
    channel =
        InProcessChannelBuilder.forName(uniqueName)
            .executor(Executors.newFixedThreadPool(4))
            .build();

    CommandServerStub stub = CommandServerGrpc.newStub(channel);
    stub.run(
        RunRequest.getDefaultInstance(),
        new StreamObserver<RunResponse>() {
          @Override
          public void onNext(RunResponse value) {
            if (receiveCount.get() > 3) {
              channel.shutdownNow();
            }
            receiveCount.incrementAndGet();
          }

          @Override
          public void onError(Throwable t) {
            clientDone.countDown();
          }

          @Override
          public void onCompleted() {
            clientDone.countDown();
          }
        });
    serverDone.await();
    clientDone.await();
    server.shutdown();
    server.awaitTermination();
  }

  @Test
  public void testInterruptFlowControl() throws Exception {
    // This test attempts to verify that FlowControl does not hang if the current thread is
    // interrupted. The initial implementation of FlowControl (which was never submitted) would go
    // into an infinite loop holding the lock on FlowControl. This would prevent any other thread
    // from obtaining the lock on FlowControl, and hang the entire process. I have confirmed that
    // this test fails with the original faulty implementation of FlowControl.
    CountDownLatch serverDone = new CountDownLatch(1);
    CountDownLatch clientDone = new CountDownLatch(1);
    AtomicInteger sentCount = new AtomicInteger();
    AtomicInteger receiveCount = new AtomicInteger();
    CommandServerGrpc.CommandServerImplBase serverImpl =
        new CommandServerGrpc.CommandServerImplBase() {
          @Override
          public void run(RunRequest request, StreamObserver<RunResponse> observer) {
            ServerCallStreamObserver<RunResponse> serverCallStreamObserver =
                (ServerCallStreamObserver<RunResponse>) observer;
            BlockingStreamObserver<RunResponse> blockingStreamObserver =
                new BlockingStreamObserver<>(serverCallStreamObserver);
            Thread t =
                new Thread(
                    () -> {
                      RunResponse response =
                          RunResponse.newBuilder()
                              .setStandardOutput(ByteString.copyFrom(new byte[1024]))
                              .build();
                      // We want to trigger isReady() -> false, and we use sentCount to control
                      // whether to
                      // sleep on the client side. Therefore, we only set sentCount after isReady()
                      // changes.
                      int sent = 0;
                      while (serverCallStreamObserver.isReady()) {
                        blockingStreamObserver.onNext(response);
                        sent++;
                      }
                      sentCount.set(sent);
                      // If the current thread is interrupted, the subsequent onNext calls should
                      // not
                      // hang, but complete eventually (they may block on flow control).
                      Thread.currentThread().interrupt();
                      for (int i = 0; i < 10; i++) {
                        blockingStreamObserver.onNext(response);
                        sentCount.incrementAndGet();
                      }
                      blockingStreamObserver.onCompleted();
                      serverDone.countDown();
                    });
            t.start();
          }
        };

    String uniqueName = InProcessServerBuilder.generateName();
    // Do not use .directExecutor here, as it makes both client and server run in the same thread.
    server =
        InProcessServerBuilder.forName(uniqueName)
            .addService(serverImpl)
            .executor(Executors.newFixedThreadPool(4))
            .build()
            .start();
    channel =
        InProcessChannelBuilder.forName(uniqueName)
            .executor(Executors.newFixedThreadPool(4))
            .build();

    CommandServerStub stub = CommandServerGrpc.newStub(channel);
    stub.run(
        RunRequest.getDefaultInstance(),
        new StreamObserver<RunResponse>() {
          @Override
          public void onNext(RunResponse value) {
            if (sentCount.get() == 0) {
              try {
                Thread.sleep(1);
              } catch (InterruptedException e) {
                throw new IllegalStateException(e);
              }
            }
            receiveCount.incrementAndGet();
          }

          @Override
          public void onError(Throwable t) {
            throw new IllegalStateException(t);
          }

          @Override
          public void onCompleted() {
            clientDone.countDown();
          }
        });
    serverDone.await();
    clientDone.await();
    assertThat(sentCount.get()).isEqualTo(receiveCount.get());
    server.shutdown();
    server.awaitTermination();
  }

  @Test
  public void testIdleTasks() throws Exception {
    CountDownLatch idleTaskRunning = new CountDownLatch(1);
    AtomicReference<ImmutableList<IdleTask.Result>> idleTaskResults = new AtomicReference<>();

    IdleTask idleTask =
        new IdleTask() {
          @Override
          public String displayName() {
            return "task";
          }

          @Override
          public void run() {
            idleTaskRunning.countDown();
          }
        };

    CommandDispatcher dispatcher =
        (invocationPolicy,
            args,
            outErr,
            lockingMode,
            uiVerbosity,
            clientDescription,
            firstContactTimeMillis,
            startupOptionsTaggedWithBazelRc,
            idleTaskResultsSupplier,
            commandExtensions,
            commandExtensionReporter) -> {
          if (args.contains("1")) {
            return BlazeCommandResult.withIdleTasks(
                BlazeCommandResult.success(), ImmutableList.of(idleTask));
          } else if (args.contains("2")) {
            idleTaskResults.set(idleTaskResultsSupplier.get());
            return BlazeCommandResult.success();
          }
          throw new IllegalStateException("Unexpected command");
        };

    createServer(dispatcher);
    CommandServerStub stub = CommandServerGrpc.newStub(channel);

    List<RunResponse> firstCmdResponses = new ArrayList<>();
    CountDownLatch firstCmdDone = new CountDownLatch(1);
    stub.run(createRequest("1"), createResponseObserver(firstCmdResponses, firstCmdDone));
    firstCmdDone.await();

    idleTaskRunning.await();

    List<RunResponse> secondCmdResponses = new ArrayList<>();
    CountDownLatch secondCmdDone = new CountDownLatch(1);
    stub.run(createRequest("2"), createResponseObserver(secondCmdResponses, secondCmdDone));
    secondCmdDone.await();

    server.shutdown();
    server.awaitTermination();

    assertThat(
            idleTaskResults.get().stream()
                .map(s -> new IdleTask.Result(s.name(), s.status(), Duration.ZERO)))
        .containsExactly(new IdleTask.Result("task", IdleTask.Status.SUCCESS, Duration.ZERO));
  }

  private static StreamObserver<RunResponse> createResponseObserver(
      List<RunResponse> responses, CountDownLatch done) {
    return new StreamObserver<RunResponse>() {
      @Override
      public void onNext(RunResponse value) {
        responses.add(value);
      }

      @Override
      public void onError(Throwable t) {
        done.countDown();
      }

      @Override
      public void onCompleted() {
        done.countDown();
      }
    };
  }

  private static CommandDispatcher throwingDispatcher() {
    return (invocationPolicy,
        args,
        outErr,
        lockingMode,
        uiVerbosity,
        clientDescription,
        firstContactTimeMillis,
        startupOptionsTaggedWithBazelRc,
        idleTaskResultsSupplier,
        commandExtensions,
        commandExtensionReporter) -> {
      throw new IllegalStateException("Command exec not expected");
    };
  }
}
