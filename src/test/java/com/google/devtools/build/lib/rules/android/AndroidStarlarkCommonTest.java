// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.android;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.rules.java.JavaCompilationArgsProvider;
import com.google.devtools.build.lib.rules.java.JavaInfo;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link AndroidStarlarkCommon}. */
@RunWith(JUnit4.class)
public class AndroidStarlarkCommonTest extends BuildViewTestCase {

  @Before
  public void setupCcToolchain() throws Exception {
    getAnalysisMock().ccSupport().setupCcToolchainConfigForCpu(mockToolsConfig, "armeabi-v7a");
  }

  @Test
  public void enableImplicitSourcelessDepsExportsCompatibilityTest() throws Exception {
    setBuildLanguageOptions(
        "--experimental_enable_android_migration_apis", "--experimental_google_legacy_api");
    scratch.file(
        "java/android/compatible.bzl",
        """
        load("@rules_java//java/common:java_info.bzl", "JavaInfo")

        def _impl(ctx):
            return [
                android_common.enable_implicit_sourceless_deps_exports_compatibility(
                    ctx.attr.dep[JavaInfo],
                ),
            ]

        my_rule = rule(
            implementation = _impl,
            attrs = dict(
                dep = attr.label(),
            ),
        )
        """);
    scratch.file("java/android/Foo.java", "public class Foo {}");
    scratch.file("java/android/MyPlugin.java", "public class MyPlugin {}");
    scratch.file(
        "java/android/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library", "java_plugin")
        load(":compatible.bzl", "my_rule")

        java_plugin(
            name = "my_plugin",
            srcs = ["MyPlugin.java"],
        )

        java_library(
            name = "foo",
            srcs = ["Foo.java"],
            exported_plugins = [":my_plugin"],
        )

        my_rule(
            name = "bar",
            dep = ":foo",
        )
        """);
    JavaInfo fooJavaInfo = JavaInfo.getJavaInfo(getConfiguredTarget("//java/android:foo"));
    JavaInfo barJavaInfo = JavaInfo.getJavaInfo(getConfiguredTarget("//java/android:bar"));
    assertThat(barJavaInfo.getProvider(JavaCompilationArgsProvider.class))
        .isEqualTo(fooJavaInfo.getProvider(JavaCompilationArgsProvider.class));
    assertThat(fooJavaInfo.getJavaPluginInfo()).isNotNull();
    assertThat(barJavaInfo.getJavaPluginInfo()).isNull();
  }
}
