// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.commands;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.bazel.bzlmod.BazelFetchAllValue.RepositoryType;
import com.google.devtools.build.lib.bazel.bzlmod.BazelFetchAllValue;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.PackageOptions;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.KeepGoingOption;
import com.google.devtools.build.lib.runtime.LoadingPhaseThreadsOption;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.FetchCommand.Code;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.InterruptedFailureDetails;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.OptionsParsingResult;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** Fetches external repositories into a specified directory. */
@Command(
    name = VendorCommand.NAME,
    options = {
      PackageOptions.class,
      KeepGoingOption.class,
      LoadingPhaseThreadsOption.class
    },
    help = "resource:vendor.txt",
    shortDescription = "Fetches external repositories into a specific folder specified by the flag "
        + "--vendor_dir."
    )
public final class VendorCommand implements BlazeCommand {
  public static final String NAME = "vendor";

  //TODO(salmasamy) decide on name and format
  private static final String VENDOR_IGNORE = ".vendorignore";

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    RepositoryOptions repoOptions = options.getOptions(RepositoryOptions.class);
    if (!options.getOptions(BuildLanguageOptions.class).enableBzlmod) {
      return createFailedBlazeCommandResult(
          env.getReporter(),
          "Bzlmod has to be enabled for vendoring to work, run with --enable_bzlmod");
    }
    if (repoOptions.vendorDirectory == null) {
      return createFailedBlazeCommandResult(
          env.getReporter(), Code.OPTIONS_INVALID, "You cannot run vendor without "
              + "specifying --vendor_dir");
    }

    //1. Fetch all repos
    LoadingPhaseThreadsOption threadsOption = options.getOptions(LoadingPhaseThreadsOption.class);
    SkyframeExecutor skyframeExecutor = env.getSkyframeExecutor();
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setParallelism(threadsOption.threads)
            .setEventHandler(env.getReporter())
            .build();
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(
                RepositoryDelegatorFunction.VENDOR_COMMAND, true)));

    SkyKey fetchKey = BazelFetchAllValue.key(false);
    try {
      env.syncPackageLoading(options);
      EvaluationResult<SkyValue> evaluationResult =
          skyframeExecutor.prepareAndGet(ImmutableSet.of(fetchKey), evaluationContext);
      if (evaluationResult.hasError()) {
        Exception e = evaluationResult.getError().getException();
        return createFailedBlazeCommandResult(
            env.getReporter(),
            e != null ? e.getMessage() : "Unexpected error during fetching all external deps.");
      }

      //2. Vendor repos
      BazelFetchAllValue fetchAllValue = (BazelFetchAllValue) evaluationResult.get(fetchKey);
      vendorRepos(env, repoOptions.vendorDirectory, fetchAllValue.getAllRepos());

      return BlazeCommandResult.success();
    } catch (AbruptExitException e) {
      return createFailedBlazeCommandResult(
          env.getReporter(), "Unknown error:: " + e.getMessage(), e.getDetailedExitCode());
    } catch (InterruptedException e) {
      return createFailedBlazeCommandResult(
          env.getReporter(), "Fetch interrupted: " + e.getMessage());
    } catch (IOException e) {
      return createFailedBlazeCommandResult(
          env.getReporter(), "Error while vendoring repos: " + e.getMessage());
    }
  }

  private void vendorRepos(CommandEnvironment env, PathFragment vendorDirectory,
      ImmutableMap<RepositoryName, RepositoryType> allRepos) throws IOException {
    Path vendorPath = vendorDirectory.isAbsolute()
        ? env.getRuntime().getFileSystem().getPath(vendorDirectory)
        : env.getWorkingDirectory().getRelative(vendorDirectory);
    Path externalPath = env.getDirectories().getOutputBase()
        .getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION);
    Path vendorIgnore = vendorPath.getRelative(VENDOR_IGNORE);

    if(!vendorPath.exists()) {
      vendorPath.createDirectory();
    }

    // exclude any local or config repos
    ImmutableList<RepositoryName> reposToVendor = allRepos.entrySet().stream()
        .filter(entry ->
            entry.getValue() != RepositoryType.LOCAL &&
                entry.getValue() != RepositoryType.CONFIGURE)
        .map(Map.Entry::getKey)
        .collect(ImmutableList.toImmutableList());

    //exclude any ignored repo under .vendorignore
    if(vendorIgnore.exists()) {
      ImmutableList<String> ignoredRepos = FileSystemUtils.readLines(vendorIgnore, UTF_8);
      reposToVendor = reposToVendor.stream()
          .filter(repo -> !ignoredRepos.contains(repo.getName()))
          .collect(toImmutableList());
    } else {
      FileSystemUtils.createEmptyFile(vendorIgnore);
    }

    //Update "out-of-date" repos under vendoring
    for(RepositoryName repo : reposToVendor) {
      if(!isRepoUpToDate(repo.getName(), vendorPath, externalPath)) {
        Path repoUnderVendor = vendorPath.getRelative(repo.getName());
        if(!repoUnderVendor.exists()) {
          repoUnderVendor.createDirectory();
        }
        FileSystemUtils.copyTreesBelow(
            externalPath.getRelative(repo.getName()),
            repoUnderVendor,
            Symlinks.NOFOLLOW);
        FileSystemUtils.copyFile(
            externalPath.getChild("@" + repo.getName() + ".marker"),
            vendorPath.getChild("@" + repo.getName() + ".marker"));
      }
    }
  }

  /** Returns whether the repo under vendor needs to be updated by comparing its marker file with
   * the one under /external */
  private boolean isRepoUpToDate(String repoName, Path vendorPath, Path externalPath) throws IOException {
    Path vendorMarkerFile = vendorPath.getChild("@" + repoName + ".marker");
    if(!vendorMarkerFile.exists()) {
      return false;
    }

    // Since this runs after vendor command (fetch all), its guaranteed that the marker files
    // under /external are up-to-date. We just need to compare it against the marker under vendor.
    Path externalMarkerFile = externalPath.getChild("@" + repoName + ".marker");
    String vendorMarkerContent = FileSystemUtils.readContent(vendorMarkerFile, UTF_8);
    String externalMarkerContent = FileSystemUtils.readContent(externalMarkerFile, UTF_8);
    return Objects.equals(vendorMarkerContent, externalMarkerContent);
  }

  private static BlazeCommandResult createFailedBlazeCommandResult(
      Reporter reporter, Code fetchCommandCode, String message) {
    return createFailedBlazeCommandResult(
        reporter,
        message,
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setFetchCommand(
                    FailureDetails.FetchCommand.newBuilder().setCode(fetchCommandCode).build())
                .build()));
  }

  private static BlazeCommandResult createFailedBlazeCommandResult(
      Reporter reporter, String errorMessage) {
    return createFailedBlazeCommandResult(
        reporter, errorMessage, InterruptedFailureDetails.detailedExitCode(errorMessage));
  }

  private static BlazeCommandResult createFailedBlazeCommandResult(
      Reporter reporter, String message, DetailedExitCode exitCode) {
    reporter.handle(Event.error(message));
    return BlazeCommandResult.detailedExitCode(exitCode);
  }
}
