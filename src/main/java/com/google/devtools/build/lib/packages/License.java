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

package com.google.devtools.build.lib.packages;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.starlarkbuildapi.LicenseApi;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import net.starlark.java.eval.Printer;

/** Support for license and distribution checking. */
@Immutable
@ThreadSafe
public final class License implements LicenseApi {
  private final ImmutableSet<LicenseType> licenseTypes;
  private final ImmutableSet<Label> exceptions;
  private static final String EXCEPTION_PREFIX = "exception=";

  /**
   * The error that's thrown if a build file contains an invalid license string.
   */
  public static class LicenseParsingException extends Exception {
    LicenseParsingException(String s) {
      super(s);
    }
  }

  /**
   * LicenseType is the basis of the License lattice - stricter licenses should
   * be declared before less-strict licenses in the enum.
   *
   * <p>Note that the order is important for the purposes of finding the least
   * restrictive license.
   */
  public enum LicenseType {
    BY_EXCEPTION_ONLY,
    RESTRICTED,
    RESTRICTED_IF_STATICALLY_LINKED,
    RECIPROCAL,
    NOTICE,
    PERMISSIVE,
    UNENCUMBERED,
    NONE
  }

  /**
   * Gets the least restrictive license type from the list of licenses declared for a target. For
   * the purposes of license checking, the license type set of a declared license can be reduced to
   * its least restrictive member.
   *
   * @param types a collection of license types
   * @return the least restrictive license type
   */
  public static LicenseType leastRestrictive(Collection<LicenseType> types) {
    // TODO(gregce): move this method to LicenseCheckingModule when Bazel's tests no longer use it
    return types.isEmpty() ? LicenseType.BY_EXCEPTION_ONLY : Collections.max(types);
  }

  /**
   * An instance of LicenseType.None with no exceptions, used for packages outside of third_party
   * which have no license clause in their BUILD files.
   */
  public static final License NO_LICENSE =
      new License(ImmutableSet.of(LicenseType.NONE), ImmutableSet.of());

  private License(ImmutableSet<LicenseType> licenseTypes, ImmutableSet<Label> exceptions) {
    // Defensive copy is done in .of()
    this.licenseTypes = licenseTypes;
    this.exceptions = exceptions;
  }

  public static License of(Collection<LicenseType> licenses, Collection<Label> exceptions) {
    ImmutableSet<LicenseType> licenseSet = ImmutableSet.copyOf(licenses);
    ImmutableSet<Label> exceptionSet = ImmutableSet.copyOf(exceptions);

    if (exceptionSet.isEmpty() && licenseSet.equals(ImmutableSet.of(LicenseType.NONE))) {
      return License.NO_LICENSE;
    }

    return new License(licenseSet, exceptionSet);
  }
  /**
   * Computes a license which can be used to check if a package is compatible
   * with some kinds of distribution. The list of licenses is scanned for the
   * least restrictive, and the exceptions are added.
   *
   * @param licStrings the list of license strings declared for the package
   * @throws LicenseParsingException if there are any parsing problems
   */
  public static License parseLicense(List<String> licStrings) throws LicenseParsingException {
    /*
     * The semantics of comparison for licenses depends on a stable iteration
     * order for both license types and exceptions. For licenseTypes, it will be
     * the comparison order from the enumerated types; for exceptions, it will
     * be lexicographic order achieved using TreeSets.
     */
    Set<LicenseType> licenseTypes = EnumSet.noneOf(LicenseType.class);
    Set<Label> exceptions = Sets.newTreeSet();
    for (String str : licStrings) {
      if (str.startsWith(EXCEPTION_PREFIX)) {
        try {
          Label label = Label.parseCanonical(str.substring(EXCEPTION_PREFIX.length()));
          exceptions.add(label);
        } catch (LabelSyntaxException e) {
          throw new LicenseParsingException(e.getMessage());
        }
      } else {
        try {
          licenseTypes.add(LicenseType.valueOf(str.toUpperCase(Locale.ENGLISH)));
        } catch (IllegalArgumentException e) {
          throw new LicenseParsingException("invalid license type: '" + str + "'");
        }
      }
    }

    return License.of(licenseTypes, exceptions);
  }

  /**
   * @return an immutable set of {@link LicenseType}s contained in this {@code
   *         License}
   */
  public Set<LicenseType> getLicenseTypes() {
    return licenseTypes;
  }

  /**
   * @return an immutable set of {@link Label}s that describe exceptions to the
   *         {@code License}
   */
  public Set<Label> getExceptions() {
    return exceptions;
  }

  @SuppressWarnings("ReferenceEquality")
  public boolean isSpecified() {
    return this != License.NO_LICENSE;
  }

  /**
   * A simple toString implementation which generates a canonical form of the
   * license. (The order of license types is guaranteed to be canonical by
   * EnumSet, and the order of exceptions is guaranteed to be lexicographic
   * order by TreeSet.)
   */
  @Override
  public String toString() {
    if (exceptions.isEmpty()) {
      return Ascii.toLowerCase(licenseTypes.toString());
    } else {
      return Ascii.toLowerCase(licenseTypes.toString()) + " with exceptions " + exceptions;
    }
  }

  /**
   * A simple equals implementation leveraging the support built into Set that
   * delegates to its contents.
   */
  @Override
  public boolean equals(Object o) {
    return o == this || (o instanceof License
        && ((License) o).licenseTypes.equals(this.licenseTypes)
        && ((License) o).exceptions.equals(this.exceptions));
  }

  /**
   * A simple hashCode implementation leveraging the support built into Set that
   * delegates to its contents.
   */
  @Override
  public int hashCode() {
    return licenseTypes.hashCode() * 43 + exceptions.hashCode();
  }

  @Override
  public boolean isImmutable() {
    return true; // licences are Starlark-hashable
  }

  /**
   * Represents the License as a canonically ordered list of strings that can be parsed by {@link
   * License#parseLicense} to get back an equal License.
   */
  @Override
  public void repr(Printer printer) {
    // The order of license types is guaranteed to be canonical by EnumSet, and the order of
    // exceptions is guaranteed to be lexicographic order by TreeSet.
    printer.printList(
        Stream.concat(
                licenseTypes.stream().map(licenseType -> Ascii.toLowerCase(licenseType.toString())),
                exceptions.stream().map(label -> EXCEPTION_PREFIX + label.getCanonicalForm()))
            .collect(toImmutableList()),
        "[",
        ", ",
        "]");
  }
}
