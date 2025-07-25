// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.docgen;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.FileTypeSet;
import java.util.regex.Pattern;

/**
 * All the constants for the Docgen project.
 */
public class DocgenConsts {

  public static final String LS = "\n";

  public static final String BE_TEMPLATE_DIR =
      "com/google/devtools/build/docgen/templates/be";
  public static final String SINGLE_BE_TEMPLATE = BE_TEMPLATE_DIR + "/single-page.vm";
  public static final String COMMON_DEFINITIONS_TEMPLATE =
      BE_TEMPLATE_DIR + "/common-definitions.vm";
  public static final String OVERVIEW_TEMPLATE = BE_TEMPLATE_DIR + "/overview.vm";
  public static final String RULES_TEMPLATE = BE_TEMPLATE_DIR + "/rules.vm";
  public static final String BE_NAV_TEMPLATE = BE_TEMPLATE_DIR + "/be-nav.vm";
  public static final String BE_TOC_TEMPLATE = BE_TEMPLATE_DIR + "/be-toc.vm";

  public static final String STARLARK_LIBRARY_TEMPLATE =
      "com/google/devtools/build/docgen/templates/starlark-library.vm";
  public static final String STARLARK_MODULE_CATEGORY_TEMPLATE =
      "com/google/devtools/build/docgen/templates/starlark-category.vm";
  public static final String STARLARK_OVERVIEW_TEMPLATE =
      "com/google/devtools/build/docgen/templates/starlark-overview.vm";
  public static final String STARLARK_TOC_TEMPLATE =
      "com/google/devtools/build/docgen/templates/starlark-toc.vm";

  public static final String VAR_LEFT_PANEL = "LEFT_PANEL";

  public static final String VAR_SECTION_BINARY = "SECTION_BINARY";
  public static final String VAR_SECTION_LIBRARY = "SECTION_LIBRARY";
  public static final String VAR_SECTION_TEST = "SECTION_TEST";
  public static final String VAR_SECTION_OTHER = "SECTION_OTHER";

  public static final String VAR_IMPLICIT_OUTPUTS = "IMPLICIT_OUTPUTS";
  public static final String VAR_NAME = "NAME";
  public static final String VAR_LANG_SPECIFIC_HEADER_TABLE = "LANG_SPECIFIC_HEADER_TABLE";
  public static final String VAR_OTHER_RULES_HEADER_TABLE = "OTHER_RULES_HEADER_TABLE";
  public static final String VAR_COMMON_ATTRIBUTE_DEFINITION = "COMMON_ATTRIBUTE_DEFINITION";
  public static final String VAR_TEST_ATTRIBUTE_DEFINITION = "TEST_ATTRIBUTE_DEFINITION";
  public static final String VAR_BINARY_ATTRIBUTE_DEFINITION = "BINARY_ATTRIBUTE_DEFINITION";

  public static final String VAR_SECTION_STARLARK_BUILTIN = "SECTION_BUILTIN";

  public static final String TYPICAL_ATTRIBUTES = "typical";
  public static final String COMMON_ATTRIBUTES = "common";
  public static final String TEST_ATTRIBUTES = "test";
  public static final String BINARY_ATTRIBUTES = "binary";

  /**
   * Mark the attribute as deprecated in the Build Encyclopedia.
   */
  public static final String FLAG_DEPRECATED = "DEPRECATED";
  public static final String FLAG_GENERIC_RULE = "GENERIC_RULE";

  public static final String HEADER_COMMENT = Joiner.on("\n").join(ImmutableList.<String>of(
      "<!DOCTYPE html>",
      "<!--",
      " This document is synchronized with Bazel releases.",
      " To edit, submit changes to the Bazel source code.",
      "-->",
      "",
      "<!-- Generated by //java/com/google/devtools/build/docgen:build-encyclopedia.zip -->"));

  public static final FileTypeSet JAVA_SOURCE_FILE_SUFFIX = FileTypeSet.of(FileType.of(".java"));

  public static final String META_KEY_NAME = "NAME";
  public static final String META_KEY_TYPE = "TYPE";
  public static final String META_KEY_FAMILY = "FAMILY";

  /**
   * For Starlark rules, this type name is equivalent to {@link RuleType#OTHER} with the {@link
   * FLAG_GENERIC_RULE} flag set.
   *
   * <p>Example: "generic_rules.genrule" would be classified as a generic rule of type OTHER.
   */
  public static final String STARLARK_GENERIC_RULE_TYPE = "GENERIC";

  /**
   * Types a rule can have (Binary, Library, Test, Flag or Other).
   *
   * <p>{@code FLAG} is a ruleset flag, like a Java or Go flag. While flags aren't rules, the doc
   * generator reads .bzl rule definitions from stardoc. So we create fake rule definitions for
   * flags to document them. We should ideally replace this with a proper documentation API for
   * Starlark flags.
   */
  public static enum RuleType {
    BINARY,
    LIBRARY,
    TEST,
    FLAG,
    OTHER
  }

  /**
   * Reference to another rule or Build Encyclopedia section.
   *
   * <p>The format of a link reference is rule.attribute (e.g. cc_library.deps). In the case of
   * static pages such as common definitions the format is page.heading
   * (e.g. common-definitions.label-expansion).
   */
  public static final Pattern BLAZE_RULE_LINK = Pattern.compile(
      "\\$\\{link (([a-zA-Z_-]+)(\\.([a-zA-Z_\\.-]+))?)\\}");

  public static final Pattern BLAZE_RULE_HEADING_LINK =
      Pattern.compile("\\$\\{link (([a-zA-Z_-]+)\\#([a-zA-Z0-9_\\.-]+))\\}");

  /**
   * i.e.
   * <!-- #FAMILY_SUMMARY -->
   */
  public static final Pattern FAMILY_SUMMARY_START =
      Pattern.compile("([\\s]*/\\*)?[\\s]*\\<!--[\\s]*#FAMILY_SUMMARY[\\s]*--\\>[\\s]*");

  /**
   * i.e.
   * <!-- #END_FAMILY_SUMMARY -->
   */
  public static final Pattern FAMILY_SUMMARY_END =
      Pattern.compile("[\\s]*\\<!--[\\s]*#END_FAMILY_SUMMARY[\\s]*--\\>[\\s]*(\\*/[\\s]*)?");

  /**
   * i.e. <!-- #BLAZE_RULE(NAME = RULE_NAME, TYPE = RULE_TYPE, FAMILY = RULE_FAMILY) -->
   * i.e. <!-- #BLAZE_RULE(...)[DEPRECATED] -->
   */
  public static final Pattern BLAZE_RULE_START = Pattern.compile(
      "^[\\s]*/\\*[\\s]*\\<!\\-\\-[\\s]*#BLAZE_RULE[\\s]*\\(([\\w\\s=,+/()-]+)\\)"
      + "(\\[[\\w,]+\\])?[\\s]*\\-\\-\\>");
  /**
   * i.e. <!-- #END_BLAZE_RULE -->
   */
  public static final Pattern BLAZE_RULE_END = Pattern.compile(
      "^[\\s]*\\<!\\-\\-[\\s]*#END_BLAZE_RULE[\\s]*\\-\\-\\>[\\s]*\\*/");
  /**
   * i.e. <!-- #BLAZE_RULE(RULE_NAME).VARIABLE_NAME -->
   */
  public static final Pattern BLAZE_RULE_VAR_START = Pattern.compile(
      "^[\\s]*/\\*[\\s]*\\<!\\-\\-[\\s]*#BLAZE_RULE\\(([\\w\\$]+)\\)\\.([\\w]+)[\\s]*\\-\\-\\>");
  /**
   * i.e. <!-- #END_BLAZE_RULE.VARIABLE_NAME -->
   */
  public static final Pattern BLAZE_RULE_VAR_END = Pattern.compile(
      "^[\\s]*\\<!\\-\\-[\\s]*#END_BLAZE_RULE\\.([\\w]+)[\\s]*\\-\\-\\>[\\s]*\\*/");
  /**
   * i.e. <!-- #BLAZE_RULE(RULE_NAME).ATTRIBUTE(ATTR_NAME) -->
   * i.e. <!-- #BLAZE_RULE(RULE_NAME).ATTRIBUTE(ATTR_NAME)[DEPRECATED] -->
   */
  public static final Pattern BLAZE_RULE_ATTR_START = Pattern.compile(
      "^[\\s]*/\\*[\\s]*\\<!\\-\\-[\\s]*#BLAZE_RULE\\(([\\w\\$]+)\\)\\."
      + "ATTRIBUTE\\(([\\w]+)\\)(\\[[\\w,]+\\])?[\\s]*\\-\\-\\>");
  /**
   * i.e. <!-- #END_BLAZE_RULE.ATTRIBUTE -->
   */
  public static final Pattern BLAZE_RULE_ATTR_END = Pattern.compile(
      "^[\\s]*\\<!\\-\\-[\\s]*#END_BLAZE_RULE\\.ATTRIBUTE[\\s]*\\-\\-\\>[\\s]*\\*/");

  /** e.g. "[DEPRECATED]" in &lt;!-- #BLAZE_RULE(...).ATTRIBUTE(...)[DEPRECATED] --&gt; */
  public static final Pattern BLAZE_RULE_FLAGS = Pattern.compile("^.*\\[(.*)\\].*$");

  /**
   * Example:
   *
   * <pre>
   * """
   * FooLang
   *
   * Note that native FooLang rules are experimental.
   * """
   * </pre>
   *
   * where "FooLang" is the family name and "Note that ..." is the summary.
   */
  public static final Pattern STARDOC_OUTPUT_FAMILY_NAME_AND_SUMMARY =
      Pattern.compile("^(?<family>[^\n]+)(?:\n\n(?<summary>[^\n].*))?$");

  /**
   * Example: "library_rules.java_import", where "library" is the rule type and "java_import" is the
   * rule name.
   */
  public static final Pattern STARDOC_OUTPUT_RULE_NAME =
      Pattern.compile("^(?<type>[^.\\s]+)_rules\\.(?<name>[^.\\s]+)$");

  /**
   * Examples:
   *
   * <ul>
   *   <li>"Deprecated: Use <code>other_attribute</code> instead."
   *   <li>"(File|str) Deprecated: Use <code>other_field</code> instead."
   * </ul>
   */
  public static final Pattern STARDOC_OUTPUT_DEPRECATED_DOCSTRING =
      Pattern.compile("^(?:\\(.*\\) )?Deprecated: (?<reason>.+)$");

  public static final ImmutableMap<String, Integer> ATTRIBUTE_ORDERING =
      ImmutableMap.<String, Integer>builder()
          .put("name", -99)
          .put("deps", -98)
          .put("src", -97)
          .put("srcs", -96)
          .put("data", -95)
          .put("resource", -94)
          .put("resources", -93)
          .put("out", -92)
          .put("outs", -91)
          .put("hdrs", -90)
          .buildOrThrow();

  // The following variables are not constants as they can be overridden from
  // StarlarkDocumentationProcessor#parseOptions
  // Their purpose is to allow generated Starlark documentation to link into the build encyclopedia.

  // Root directory of *narrative* Starlark documentation files such as rules.md
  public static String starlarkDocsRoot = "/rules";

  static String toCommandLineFormat(String cmdDoc) {
    // Replace html <br> tags with line breaks
    cmdDoc = cmdDoc.replaceAll("(<br>|<br[\\s]*/>)", "\n") + "\n";
    // Replace other links <a href=".*">s with human readable links
    cmdDoc = cmdDoc.replaceAll("\\<a href=\"([^\"]+)\">[^\\<]*\\</a\\>", "$1");
    // Delete other html tags
    cmdDoc = cmdDoc.replaceAll("\\<[/]?[^\\>]+\\>", "");
    // Delete docgen variables
    cmdDoc = cmdDoc.replaceAll("\\$\\{[\\w_]+\\}", "");
    // Substitute more than 2 line breaks in a row with 2 line breaks
    cmdDoc = cmdDoc.replaceAll("[\\n]{2,}", "\n\n");
    // Ensure that the doc starts and ends with exactly two line breaks
    cmdDoc = cmdDoc.replaceAll("^[\\n]+", "\n\n");
    cmdDoc = cmdDoc.replaceAll("[\\n]+$", "\n\n");
    return cmdDoc;
  }

  static String removeDuplicatedNewLines(String doc) {
    return doc.replaceAll("[\\n][\\s]*[\\n]", "\n");
  }
}
