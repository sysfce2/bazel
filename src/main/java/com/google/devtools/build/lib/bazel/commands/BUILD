load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["*"]),
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "commands",
    srcs = glob(
        ["*.java"],
    ),
    resources = [
        "fetch.txt",
        "mod.txt",
        "vendor.txt",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:keep-going-option",
        "//src/main/java/com/google/devtools/build/lib:loading-phase-threads-option",
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib:runtime/blaze_command_result",
        "//src/main/java/com/google/devtools/build/lib/analysis:configured_target",
        "//src/main/java/com/google/devtools/build/lib/analysis:no_build_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:no_build_request_finished_event",
        "//src/main/java/com/google/devtools/build/lib/analysis/config:core_options",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:common",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:inspection",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:module_extension",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:resolution",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:root_module_file_fixup",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:tidy",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:vendor",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod/modcommand",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository:repo_definition",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository:repo_definition_value",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository:repository_options",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository/downloader",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/pkgcache:package_options",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/repository_directory_value",
        "//src/main/java/com/google/devtools/build/lib/runtime/commands",
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/skyframe:bzl_load_cycle_reporter",
        "//src/main/java/com/google/devtools/build/lib/skyframe:bzlmod_repo_cycle_reporter",
        "//src/main/java/com/google/devtools/build/lib/skyframe:precomputed_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:repository_mapping_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:sky_functions",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skyframe_cluster",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:command",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/util:interrupted_failure_details",
        "//src/main/java/com/google/devtools/build/lib/util:maybe_complete_set",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:gson",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)
