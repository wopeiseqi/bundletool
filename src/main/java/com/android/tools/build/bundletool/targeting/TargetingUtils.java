/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.tools.build.bundletool.targeting;

import static com.android.tools.build.bundletool.utils.TargetingProtoUtils.sdkVersionTargeting;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.android.bundle.Targeting.AssetsDirectoryTargeting;
import com.android.bundle.Targeting.SdkVersion;
import com.android.bundle.Targeting.SdkVersionTargeting;
import com.android.bundle.Targeting.VariantTargeting;
import com.android.tools.build.bundletool.model.ModuleSplit;
import com.android.tools.build.bundletool.utils.TargetingProtoUtils;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

/** Utility functions for Targeting proto. */
public final class TargetingUtils {

  /** Returns the targeting dimensions of the targeting proto. */
  public static ImmutableList<TargetingDimension> getTargetingDimensions(
      AssetsDirectoryTargeting targeting) {
    ImmutableList.Builder<TargetingDimension> dimensions = new ImmutableList.Builder<>();
    if (targeting.hasAbi()) {
      dimensions.add(TargetingDimension.ABI);
    }
    if (targeting.hasGraphicsApi()) {
      dimensions.add(TargetingDimension.GRAPHICS_API);
    }
    if (targeting.hasTextureCompressionFormat()) {
      dimensions.add(TargetingDimension.TEXTURE_COMPRESSION_FORMAT);
    }
    if (targeting.hasLanguage()) {
      dimensions.add(TargetingDimension.LANGUAGE);
    }
    return dimensions.build();
  }

  /**
   * Checks if the sdk range supported by the variant is completely enclosed in the sdk range
   * supported by the moduleSplit. Also ensures that variant does not partially cover a module split
   * range.
   */
  private static boolean checkVariantMatchWithModuleSplit(
      VariantTargeting variantTargeting, ModuleSplit moduleSplit) {

    SdkVersionTargeting variantSdkTargeting = variantTargeting.getSdkVersionTargeting();
    int variantTargetingMinSdk = getMinSdk(variantSdkTargeting);
    int variantTargetingMaxSdk = getMaxSdk(variantSdkTargeting);

    SdkVersionTargeting moduleSplitSdkTargeting =
        moduleSplit.getVariantTargeting().getSdkVersionTargeting();
    int moduleSplitMinSdk = getMinSdk(moduleSplitSdkTargeting);
    int moduleSplitMaxSdk = getMaxSdk(moduleSplitSdkTargeting);

    boolean encloses =
        variantTargetingMinSdk >= moduleSplitMinSdk && variantTargetingMaxSdk <= moduleSplitMaxSdk;

    if (!encloses) {
      checkState(
          variantTargetingMaxSdk <= moduleSplitMinSdk
              || moduleSplitMaxSdk <= variantTargetingMinSdk,
          "Partial overlap between the sdk ranges of "
              + "variant [ %s, %s )  and module split [ %s, %s ).",
          variantTargetingMinSdk,
          variantTargetingMaxSdk,
          moduleSplitMinSdk,
          moduleSplitMaxSdk);
      return false;
    }
    return true;
  }

  /** Returns a map of variants with a list of all the splits supported by each variant. */
  public static ImmutableMultimap<VariantTargeting, ModuleSplit> matchModuleSplitWithVariants(
      ImmutableCollection<VariantTargeting> variants,
      ImmutableCollection<ModuleSplit> moduleSplits) {

    ImmutableMultimap.Builder<VariantTargeting, ModuleSplit> mapping = ImmutableMultimap.builder();

    for (VariantTargeting variant : variants) {
      for (ModuleSplit moduleSplit : moduleSplits) {
        if (checkVariantMatchWithModuleSplit(variant, moduleSplit)) {
          mapping.put(variant, moduleSplit);
        }
      }
    }
    return mapping.build();
  }

  /**
   * Given a set of potentially overlapping variant targetings generate smallest set of disjoint
   * variant targetings covering all of them.
   *
   * <p>Assumption: All Variants only support sdk targeting.
   */
  public static ImmutableSet<VariantTargeting> generateAllVariantTargetings(
      ImmutableSet<VariantTargeting> variantTargetings) {

    if (variantTargetings.size() <= 1) {
      return variantTargetings;
    }

    ImmutableList<SdkVersionTargeting> sdkVersionTargetings =
        generateAllSdkTargetings(
            variantTargetings
                .stream()
                .map(variantTargeting -> variantTargeting.getSdkVersionTargeting())
                .collect(toImmutableList()));

    return sdkVersionTargetings
        .stream()
        .map(
            sdkVersionTargeting ->
                VariantTargeting.newBuilder().setSdkVersionTargeting(sdkVersionTargeting).build())
        .collect(toImmutableSet());
  }

  /**
   * Given a set of potentially overlapping sdk targetings generate set of disjoint sdk targetings
   * covering all of them.
   *
   * <p>Assumption: There are no sdk range gaps in targetings.
   */
  private static ImmutableList<SdkVersionTargeting> generateAllSdkTargetings(
      ImmutableList<SdkVersionTargeting> sdkVersionTargetings) {

    sdkVersionTargetings.forEach(
        sdkVersionTargeting -> checkState(sdkVersionTargeting.getValueList().size() == 1));

    ImmutableList<Integer> minSdkValues =
        sdkVersionTargetings
            .stream()
            .map(sdkVersionTargeting -> sdkVersionTargeting.getValue(0).getMin().getValue())
            .distinct()
            .sorted()
            .collect(toImmutableList());

    ImmutableSet<SdkVersion> sdkVersions =
        minSdkValues.stream().map(TargetingProtoUtils::sdkVersionFrom).collect(toImmutableSet());

    return sdkVersions
        .stream()
        .map(
            sdkVersion ->
                sdkVersionTargeting(
                    sdkVersion,
                    Sets.difference(sdkVersions, ImmutableSet.of(sdkVersion)).immutableCopy()))
        .collect(toImmutableList());
  }

  /** Extracts the minimum sdk (inclusive) supported by the targeting. */
  private static int getMinSdk(SdkVersionTargeting sdkVersionTargeting) {
    if (sdkVersionTargeting.getValueList().isEmpty()) {
      return 1;
    }
    return sdkVersionTargeting.getValue(0).getMin().getValue();
  }

  /** Extracts the maximum sdk (exclusive) supported by the targeting. */
  private static int getMaxSdk(SdkVersionTargeting sdkVersionTargeting) {
    int minSdk = getMinSdk(sdkVersionTargeting);
    int alternativeMinSdk =
        sdkVersionTargeting
            .getAlternativesList()
            .stream()
            .mapToInt(alternativeSdk -> alternativeSdk.getMin().getValue())
            .filter(sdkValue -> minSdk < sdkValue)
            .min()
            .orElse(Integer.MAX_VALUE);

    return alternativeMinSdk;
  }
}
