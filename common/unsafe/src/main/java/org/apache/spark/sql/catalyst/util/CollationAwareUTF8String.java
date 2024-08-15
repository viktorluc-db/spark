/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.catalyst.util;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import com.ibm.icu.text.BreakIterator;
import com.ibm.icu.text.Collator;
import com.ibm.icu.text.RuleBasedCollator;
import com.ibm.icu.text.StringSearch;
import com.ibm.icu.util.ULocale;

import org.apache.spark.unsafe.UTF8StringBuilder;
import org.apache.spark.unsafe.types.UTF8String;

import static org.apache.spark.unsafe.types.UTF8String.CodePointIteratorType;

import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Utility class for collation-aware UTF8String operations.
 */
public class CollationAwareUTF8String {

  /**
   * The constant value to indicate that the match is not found when searching for a pattern
   * string in a target string.
   */
  private static final int MATCH_NOT_FOUND = -1;

  /**
   * Returns whether the target string starts with the specified prefix, starting from the
   * specified position (0-based index referring to character position in UTF8String), with respect
   * to the UTF8_LCASE collation. The method assumes that the prefix is already lowercased prior
   * to method call to avoid the overhead of lowercasing the same prefix string multiple times.
   *
   * @param target the string to be searched in
   * @param lowercasePattern the string to be searched for
   * @param startPos the start position for searching (in the target string)
   * @return whether the target string starts with the specified prefix in UTF8_LCASE
   */
  private static boolean lowercaseMatchFrom(
      final UTF8String target,
      final UTF8String lowercasePattern,
      int startPos) {
    return lowercaseMatchLengthFrom(target, lowercasePattern, startPos) != MATCH_NOT_FOUND;
  }

  /**
   * Returns the length of the substring of the target string that starts with the specified
   * prefix, starting from the specified position (0-based index referring to character position
   * in UTF8String), with respect to the UTF8_LCASE collation. The method assumes that the
   * prefix is already lowercased. The method only considers the part of target string that
   * starts from the specified (inclusive) position (that is, the method does not look at UTF8
   * characters of the target string at or after position `endPos`). If the prefix is not found,
   * MATCH_NOT_FOUND is returned.
   *
   * @param target the string to be searched in
   * @param lowercasePattern the string to be searched for
   * @param startPos the start position for searching (in the target string)
   * @return length of the target substring that starts with the specified prefix in lowercase
   */
  private static int lowercaseMatchLengthFrom(
      final UTF8String target,
      final UTF8String lowercasePattern,
      int startPos) {
    assert startPos >= 0;
    // Use code point iterators for efficient string search.
    Iterator<Integer> targetIterator = target.codePointIterator();
    Iterator<Integer> patternIterator = lowercasePattern.codePointIterator();
    // Skip to startPos in the target string.
    for (int i = 0; i < startPos; ++i) {
      if (targetIterator.hasNext()) {
        targetIterator.next();
      } else {
        return MATCH_NOT_FOUND;
      }
    }
    // Compare the characters in the target and pattern strings.
    int matchLength = 0, codePointBuffer = -1, targetCodePoint, patternCodePoint;
    while (targetIterator.hasNext() && patternIterator.hasNext()) {
      if (codePointBuffer != -1) {
        targetCodePoint = codePointBuffer;
        codePointBuffer = -1;
      } else {
        // Use buffered lowercase code point iteration to handle one-to-many case mappings.
        targetCodePoint = getLowercaseCodePoint(targetIterator.next());
        if (targetCodePoint == CODE_POINT_COMBINED_LOWERCASE_I_DOT) {
          targetCodePoint = CODE_POINT_LOWERCASE_I;
          codePointBuffer = CODE_POINT_COMBINING_DOT;
        }
        ++matchLength;
      }
      patternCodePoint = patternIterator.next();
      if (targetCodePoint != patternCodePoint) {
        return MATCH_NOT_FOUND;
      }
    }
    // If the pattern string has more characters, or the match is found at the middle of a
    // character that maps to multiple characters in lowercase, then match is not found.
    if (patternIterator.hasNext() || codePointBuffer != -1) {
      return MATCH_NOT_FOUND;
    }
    // If all characters are equal, return the length of the match in the target string.
    return matchLength;
  }

  /**
   * Returns the position of the first occurrence of the pattern string in the target string,
   * starting from the specified position (0-based index referring to character position in
   * UTF8String), with respect to the UTF8_LCASE collation. The method assumes that the
   * pattern string is already lowercased prior to call. If the pattern is not found,
   * MATCH_NOT_FOUND is returned.
   *
   * @param target the string to be searched in
   * @param lowercasePattern the string to be searched for
   * @param startPos the start position for searching (in the target string)
   * @return the position of the first occurrence of pattern in target
   */
  private static int lowercaseFind(
      final UTF8String target,
      final UTF8String lowercasePattern,
      int startPos) {
    assert startPos >= 0;
    for (int i = startPos; i <= target.numChars(); ++i) {
      if (lowercaseMatchFrom(target, lowercasePattern, i)) {
        return i;
      }
    }
    return MATCH_NOT_FOUND;
  }

  /**
   * Returns whether the target string ends with the specified suffix, ending at the specified
   * position (0-based index referring to character position in UTF8String), with respect to the
   * UTF8_LCASE collation. The method assumes that the suffix is already lowercased prior
   * to method call to avoid the overhead of lowercasing the same suffix string multiple times.
   *
   * @param target the string to be searched in
   * @param lowercasePattern the string to be searched for
   * @param endPos the end position for searching (in the target string)
   * @return whether the target string ends with the specified suffix in lowercase
   */
  private static boolean lowercaseMatchUntil(
      final UTF8String target,
      final UTF8String lowercasePattern,
      int endPos) {
    return lowercaseMatchLengthUntil(target, lowercasePattern, endPos) != MATCH_NOT_FOUND;
  }

  /**
   * Returns the length of the substring of the target string that ends with the specified
   * suffix, ending at the specified position (0-based index referring to character position in
   * UTF8String), with respect to the UTF8_LCASE collation. The method assumes that the
   * suffix is already lowercased. The method only considers the part of target string that ends
   * at the specified (non-inclusive) position (that is, the method does not look at UTF8
   * characters of the target string at or after position `endPos`). If the suffix is not found,
   * MATCH_NOT_FOUND is returned.
   *
   * @param target the string to be searched in
   * @param lowercasePattern the string to be searched for
   * @param endPos the end position for searching (in the target string)
   * @return length of the target substring that ends with the specified suffix in lowercase
   */
  private static int lowercaseMatchLengthUntil(
      final UTF8String target,
      final UTF8String lowercasePattern,
      int endPos) {
    assert endPos >= 0;
    // Use code point iterators for efficient string search.
    Iterator<Integer> targetIterator = target.reverseCodePointIterator();
    Iterator<Integer> patternIterator = lowercasePattern.reverseCodePointIterator();
    // Skip to startPos in the target string.
    for (int i = endPos; i < target.numChars(); ++i) {
      if (targetIterator.hasNext()) {
        targetIterator.next();
      } else {
        return MATCH_NOT_FOUND;
      }
    }
    // Compare the characters in the target and pattern strings.
    int matchLength = 0, codePointBuffer = -1, targetCodePoint, patternCodePoint;
    while (targetIterator.hasNext() && patternIterator.hasNext()) {
      if (codePointBuffer != -1) {
        targetCodePoint = codePointBuffer;
        codePointBuffer = -1;
      } else {
        // Use buffered lowercase code point iteration to handle one-to-many case mappings.
        targetCodePoint = getLowercaseCodePoint(targetIterator.next());
        if (targetCodePoint == CODE_POINT_COMBINED_LOWERCASE_I_DOT) {
          targetCodePoint = CODE_POINT_COMBINING_DOT;
          codePointBuffer = CODE_POINT_LOWERCASE_I;
        }
        ++matchLength;
      }
      patternCodePoint = patternIterator.next();
      if (targetCodePoint != patternCodePoint) {
        return MATCH_NOT_FOUND;
      }
    }
    // If the pattern string has more characters, or the match is found at the middle of a
    // character that maps to multiple characters in lowercase, then match is not found.
    if (patternIterator.hasNext() || codePointBuffer != -1) {
      return MATCH_NOT_FOUND;
    }
    // If all characters are equal, return the length of the match in the target string.
    return matchLength;
  }

  /**
   * Returns the position of the last occurrence of the pattern string in the target string,
   * ending at the specified position (0-based index referring to character position in
   * UTF8String), with respect to the UTF8_LCASE collation. The method assumes that the
   * pattern string is already lowercased prior to call. If the pattern is not found,
   * MATCH_NOT_FOUND is returned.
   *
   * @param target the string to be searched in
   * @param lowercasePattern the string to be searched for
   * @param endPos the end position for searching (in the target string)
   * @return the position of the last occurrence of pattern in target
   */
  private static int lowercaseRFind(
      final UTF8String target,
      final UTF8String lowercasePattern,
      int endPos) {
    assert endPos <= target.numChars();
    for (int i = endPos; i >= 0; --i) {
      if (lowercaseMatchUntil(target, lowercasePattern, i)) {
        return i;
      }
    }
    return MATCH_NOT_FOUND;
  }

  /**
   * Lowercase UTF8String comparison used for UTF8_LCASE collation. This method uses lowercased
   * code points to compare the strings in a case-insensitive manner using ICU rules, taking into
   * account special rules for one-to-many case mappings (see: lowerCaseCodePoints).
   *
   * @param left The first UTF8String to compare.
   * @param right The second UTF8String to compare.
   * @return An integer representing the comparison result.
   */
  public static int compareLowerCase(final UTF8String left, final UTF8String right) {
    // Only if both strings are ASCII, we can use faster comparison (no string allocations).
    if (left.isFullAscii() && right.isFullAscii()) {
      return compareLowerCaseAscii(left, right);
    }
    return compareLowerCaseSlow(left, right);
  }

  /**
   * Fast version of the `compareLowerCase` method, used when both arguments are ASCII strings.
   *
   * @param left The first ASCII UTF8String to compare.
   * @param right The second ASCII UTF8String to compare.
   * @return An integer representing the comparison result.
   */
  private static int compareLowerCaseAscii(final UTF8String left, final UTF8String right) {
    int leftBytes = left.numBytes(), rightBytes = right.numBytes();
    for (int curr = 0; curr < leftBytes && curr < rightBytes; curr++) {
      int lowerLeftByte = Character.toLowerCase(left.getByte(curr));
      int lowerRightByte = Character.toLowerCase(right.getByte(curr));
      if (lowerLeftByte != lowerRightByte) {
        return lowerLeftByte - lowerRightByte;
      }
    }
    return leftBytes - rightBytes;
  }

  /**
   * Slow version of the `compareLowerCase` method, used when both arguments are non-ASCII strings.
   *
   * @param left The first non-ASCII UTF8String to compare.
   * @param right The second non-ASCII UTF8String to compare.
   * @return An integer representing the comparison result.
   */
  private static int compareLowerCaseSlow(final UTF8String left, final UTF8String right) {
    return lowerCaseCodePoints(left).binaryCompare(lowerCaseCodePoints(right));
  }

  /**
   * Performs string replacement for ICU collations by searching for instances of the search
   * string in the `target` string, with respect to the specified collation, and then replacing
   * them with the replace string. The method returns a new UTF8String with all instances of the
   * search string replaced using the replace string. Similar to UTF8String.findInSet behavior
   * used for UTF8_BINARY, the method returns the `target` string if the `search` string is empty.
   *
   * @param target the string to be searched in
   * @param search the string to be searched for
   * @param replace the string to be used as replacement
   * @param collationId the collation ID to use for string search
   * @return the position of the first occurrence of `match` in `set`
   */
  public static UTF8String replace(final UTF8String target, final UTF8String search,
      final UTF8String replace, final int collationId) {
    // This collation aware implementation is based on existing implementation on UTF8String
    if (target.numBytes() == 0 || search.numBytes() == 0) {
      return target;
    }

    String targetStr = target.toValidString();
    String searchStr = search.toValidString();
    StringSearch stringSearch = CollationFactory.getStringSearch(targetStr, searchStr, collationId);

    StringBuilder sb = new StringBuilder();
    int start = 0;
    int matchStart = stringSearch.first();
    while (matchStart != StringSearch.DONE) {
      sb.append(targetStr, start, matchStart);
      sb.append(replace.toValidString());
      start = matchStart + stringSearch.getMatchLength();
      matchStart = stringSearch.next();
    }
    sb.append(targetStr, start, targetStr.length());
    return UTF8String.fromString(sb.toString());
  }

  /**
   * Performs string replacement for UTF8_LCASE collation by searching for instances of the search
   * string in the target string, with respect to lowercased string versions, and then replacing
   * them with the replace string. The method returns a new UTF8String with all instances of the
   * search string replaced using the replace string. Similar to UTF8String.findInSet behavior
   * used for UTF8_BINARY, the method returns the `target` string if the `search` string is empty.
   *
   * @param target the string to be searched in
   * @param search the string to be searched for
   * @param replace the string to be used as replacement
   * @return the position of the first occurrence of `match` in `set`
   */
  public static UTF8String lowercaseReplace(final UTF8String target, final UTF8String search,
      final UTF8String replace) {
    if (target.numBytes() == 0 || search.numBytes() == 0) {
      return target;
    }

    UTF8String lowercaseSearch = lowerCaseCodePoints(search);

    int start = 0;
    int end = lowercaseFind(target, lowercaseSearch, start);
    if (end == -1) {
      // Search string was not found, so string is unchanged.
      return target;
    }

    // At least one match was found. Estimate space needed for result.
    // The 16x multiplier here is chosen to match commons-lang3's implementation.
    int increase = Math.max(0, replace.numBytes() - search.numBytes()) * 16;
    final UTF8StringBuilder buf = new UTF8StringBuilder(target.numBytes() + increase);
    while (end != -1) {
      buf.append(target.substring(start, end));
      buf.append(replace);
      // Update character positions
      start = end + lowercaseMatchLengthFrom(target, lowercaseSearch, end);
      end = lowercaseFind(target, lowercaseSearch, start);
    }
    buf.append(target.substring(start, target.numChars()));
    return buf.build();
  }

  /**
   * Convert the input string to uppercase using the ICU root locale rules.
   *
   * @param target the input string
   * @return the uppercase string
   */
  public static UTF8String toUpperCase(final UTF8String target) {
    if (target.isFullAscii()) return target.toUpperCaseAscii();
    return toUpperCaseSlow(target);
  }

  private static UTF8String toUpperCaseSlow(final UTF8String target) {
    // Note: In order to achieve the desired behavior, we use the ICU UCharacter class to
    // convert the string to uppercase, which only accepts a Java strings as input.
    return UTF8String.fromString(UCharacter.toUpperCase(target.toValidString()));
  }

  /**
   * Convert the input string to uppercase using the specified ICU collation rules.
   *
   * @param target the input string
   * @return the uppercase string
   */
  public static UTF8String toUpperCase(final UTF8String target, final int collationId) {
    if (target.isFullAscii()) return target.toUpperCaseAscii();
    return toUpperCaseSlow(target, collationId);
  }

  private static UTF8String toUpperCaseSlow(final UTF8String target, final int collationId) {
    // Note: In order to achieve the desired behavior, we use the ICU UCharacter class to
    // convert the string to uppercase, which only accepts a Java strings as input.
    ULocale locale = CollationFactory.fetchCollation(collationId)
      .collator.getLocale(ULocale.ACTUAL_LOCALE);
    return UTF8String.fromString(UCharacter.toUpperCase(locale, target.toValidString()));
  }

  /**
   * Convert the input string to lowercase using the ICU root locale rules.
   *
   * @param target the input string
   * @return the lowercase string
   */
  public static UTF8String toLowerCase(final UTF8String target) {
    if (target.isFullAscii()) return target.toLowerCaseAscii();
    return toLowerCaseSlow(target);
  }

  private static UTF8String toLowerCaseSlow(final UTF8String target) {
    // Note: In order to achieve the desired behavior, we use the ICU UCharacter class to
    // convert the string to lowercase, which only accepts a Java strings as input.
    return UTF8String.fromString(UCharacter.toLowerCase(target.toValidString()));
  }

  /**
   * Convert the input string to lowercase using the specified ICU collation rules.
   *
   * @param target the input string
   * @return the lowercase string
   */
  public static UTF8String toLowerCase(final UTF8String target, final int collationId) {
    if (target.isFullAscii()) return target.toLowerCaseAscii();
    return toLowerCaseSlow(target, collationId);
  }

  private static UTF8String toLowerCaseSlow(final UTF8String target, final int collationId) {
    // Note: In order to achieve the desired behavior, we use the ICU UCharacter class to
    // convert the string to lowercase, which only accepts a Java strings as input.
    ULocale locale = CollationFactory.fetchCollation(collationId)
      .collator.getLocale(ULocale.ACTUAL_LOCALE);
    return UTF8String.fromString(UCharacter.toLowerCase(locale, target.toValidString()));
  }

  /**
   * Converts a single code point to lowercase using ICU rules, with special handling for
   * one-to-many case mappings (i.e. characters that map to multiple characters in lowercase) and
   * context-insensitive case mappings (i.e. characters that map to different characters based on
   * string context - e.g. the position in the string relative to other characters).
   *
   * @param codePoint The code point to convert to lowercase.
   * @param sb The StringBuilder to append the lowercase character to.
   */
  private static void appendLowercaseCodePoint(final int codePoint, final StringBuilder sb) {
    int lowercaseCodePoint = getLowercaseCodePoint(codePoint);
    if (lowercaseCodePoint == CODE_POINT_COMBINED_LOWERCASE_I_DOT) {
      // Latin capital letter I with dot above is mapped to 2 lowercase characters.
      sb.appendCodePoint(0x0069);
      sb.appendCodePoint(0x0307);
    } else {
      // All other characters should follow context-unaware ICU single-code point case mapping.
      sb.appendCodePoint(lowercaseCodePoint);
    }
  }

  /**
   * `CODE_POINT_COMBINED_LOWERCASE_I_DOT` is an internal representation of the combined lowercase
   * code point for ASCII lowercase letter i with an additional combining dot character (U+0307).
   * This integer value is not a valid code point itself, but rather an artificial code point
   * marker used to represent the two lowercase characters that are the result of converting the
   * uppercase Turkish dotted letter I with a combining dot character (U+0130) to lowercase.
   */
  private static final int CODE_POINT_LOWERCASE_I = 0x69;
  private static final int CODE_POINT_COMBINING_DOT = 0x307;
  private static final int CODE_POINT_COMBINED_LOWERCASE_I_DOT =
    CODE_POINT_LOWERCASE_I << 16 | CODE_POINT_COMBINING_DOT;

  /**
   * Returns the lowercase version of the provided code point, with special handling for
   * one-to-many case mappings (i.e. characters that map to multiple characters in lowercase) and
   * context-insensitive case mappings (i.e. characters that map to different characters based on
   * the position in the string relative to other characters in lowercase).
   */
  private static int getLowercaseCodePoint(final int codePoint) {
    if (codePoint == 0x0130) {
      // Latin capital letter I with dot above is mapped to 2 lowercase characters.
      return CODE_POINT_COMBINED_LOWERCASE_I_DOT;
    }
    else if (codePoint == 0x03C2) {
      // Greek final and non-final letter sigma should be mapped the same. This is achieved by
      // mapping Greek small final sigma (U+03C2) to Greek small non-final sigma (U+03C3). Capital
      // letter sigma (U+03A3) is mapped to small non-final sigma (U+03C3) in the `else` branch.
      return 0x03C3;
    }
    else {
      // All other characters should follow context-unaware ICU single-code point case mapping.
      return UCharacter.toLowerCase(codePoint);
    }
  }

  /**
   * Converts an entire string to lowercase using ICU rules, code point by code point, with
   * special handling for one-to-many case mappings (i.e. characters that map to multiple
   * characters in lowercase). Also, this method omits information about context-sensitive case
   * mappings using special handling in the `appendLowercaseCodePoint` method.
   *
   * @param target The target string to convert to lowercase.
   * @return The string converted to lowercase in a context-unaware manner.
   */
  public static UTF8String lowerCaseCodePoints(final UTF8String target) {
    if (target.isFullAscii()) return target.toLowerCaseAscii();
    return lowerCaseCodePointsSlow(target);
  }

  private static UTF8String lowerCaseCodePointsSlow(final UTF8String target) {
    Iterator<Integer> targetIter = target.codePointIterator(
      CodePointIteratorType.CODE_POINT_ITERATOR_MAKE_VALID);
    StringBuilder sb = new StringBuilder();
    while (targetIter.hasNext()) {
      appendLowercaseCodePoint(targetIter.next(), sb);
    }
    return UTF8String.fromString(sb.toString());
  }

  /**
   * Convert the input string to titlecase using the ICU root locale rules.
   */
  public static UTF8String toTitleCase(final UTF8String target) {
    // Note: In order to achieve the desired behavior, we use the ICU UCharacter class to
    // convert the string to titlecase, which only accepts a Java strings as input.
    return UTF8String.fromString(UCharacter.toTitleCase(target.toValidString(),
      BreakIterator.getWordInstance()));
  }

  /**
   * Convert the input string to titlecase using the specified ICU collation rules.
   */
  public static UTF8String toTitleCase(final UTF8String target, final int collationId) {
    ULocale locale = CollationFactory.fetchCollation(collationId)
      .collator.getLocale(ULocale.ACTUAL_LOCALE);
    return UTF8String.fromString(UCharacter.toTitleCase(locale, target.toValidString(),
      BreakIterator.getWordInstance(locale)));
  }

  /*
   * Returns the position of the first occurrence of the match string in the set string,
   * counting ASCII commas as delimiters. The match string is compared in a collation-aware manner,
   * with respect to the specified collation ID. Similar to UTF8String.findInSet behavior used
   * for UTF8_BINARY collation, the method returns 0 if the match string contains no commas.
   *
   * @param match the string to be searched for
   * @param set the string to be searched in
   * @param collationId the collation ID to use for string comparison
   * @return the position of the first occurrence of `match` in `set`
   */
  public static int findInSet(final UTF8String match, final UTF8String set, int collationId) {
    // If the "word" string contains a comma, FindInSet should return 0.
    if (match.contains(UTF8String.fromString(","))) {
      return 0;
    }
    // Otherwise, search for commas in "set" and compare each substring with "word".
    int byteIndex = 0, charIndex = 0, wordCount = 1, lastComma = -1;
    while (byteIndex < set.numBytes()) {
      byte nextByte = set.getByte(byteIndex);
      if (nextByte == (byte) ',') {
        if (set.substring(lastComma + 1, charIndex).semanticEquals(match, collationId)) {
          return wordCount;
        }
        lastComma = charIndex;
        ++wordCount;
      }
      byteIndex += UTF8String.numBytesForFirstByte(nextByte);
      ++charIndex;
    }
    if (set.substring(lastComma + 1, set.numBytes()).semanticEquals(match, collationId)) {
      return wordCount;
    }
    // If no match is found, return 0.
    return 0;
  }

  /**
   * Checks whether the target string contains the pattern string, with respect to the UTF8_LCASE
   * collation. This method generally works with respect to code-point based comparison logic.
   *
   * @param target the string to be searched in
   * @param pattern the string to be searched for
   * @return whether the target string contains the pattern string
   */
  public static boolean lowercaseContains(final UTF8String target, final UTF8String pattern) {
    // Fast path for ASCII-only strings.
    if (target.isFullAscii() && pattern.isFullAscii()) {
      return target.toLowerCase().contains(pattern.toLowerCase());
    }
    // Slow path for non-ASCII strings.
    return CollationAwareUTF8String.lowercaseIndexOfSlow(target, pattern, 0) >= 0;
  }

  /**
   * Checks whether the target string starts with the pattern string, with respect to the UTF8_LCASE
   * collation. This method generally works with respect to code-point based comparison logic.
   *
   * @param target the string to be searched in
   * @param pattern the string to be searched for
   * @return whether the target string starts with the pattern string
   */
  public static boolean lowercaseStartsWith(final UTF8String target, final UTF8String pattern) {
    // Fast path for ASCII-only strings.
    if (target.isFullAscii() && pattern.isFullAscii()) {
      return target.toLowerCase().startsWith(pattern.toLowerCase());
    }
    // Slow path for non-ASCII strings.
    return CollationAwareUTF8String.lowercaseMatchFrom(target, lowerCaseCodePointsSlow(pattern), 0);
  }

  /**
   * Checks whether the target string ends with the pattern string, with respect to the UTF8_LCASE
   * collation. This method generally works with respect to code-point based comparison logic.
   *
   * @param target the string to be searched in
   * @param pattern the string to be searched for
   * @return whether the target string ends with the pattern string
   */
  public static boolean lowercaseEndsWith(final UTF8String target, final UTF8String pattern) {
    // Fast path for ASCII-only strings.
    if (target.isFullAscii() && pattern.isFullAscii()) {
      return target.toLowerCase().endsWith(pattern.toLowerCase());
    }
    // Slow path for non-ASCII strings.
    return CollationAwareUTF8String.lowercaseMatchUntil(target, lowerCaseCodePointsSlow(pattern),
      target.numChars());
  }

  /**
   * Returns the position of the first occurrence of the pattern string in the target string,
   * starting from the specified position (0-based index referring to character position in
   * UTF8String), with respect to the UTF8_LCASE collation. If the pattern is not found,
   * MATCH_NOT_FOUND is returned.
   *
   * @param target the string to be searched in
   * @param pattern the string to be searched for
   * @param start the start position for searching (in the target string)
   * @return the position of the first occurrence of pattern in target
   */
  public static int lowercaseIndexOf(final UTF8String target, final UTF8String pattern,
      final int start) {
    if (pattern.numChars() == 0) return target.indexOfEmpty(start);
    if (target.isFullAscii() && pattern.isFullAscii()) {
      return target.toLowerCase().indexOf(pattern.toLowerCase(), start);
    }
    return lowercaseIndexOfSlow(target, pattern, start);
  }

  private static int lowercaseIndexOfSlow(final UTF8String target, final UTF8String pattern,
      final int start) {
    return lowercaseFind(target, lowerCaseCodePoints(pattern), start);
  }

  public static int indexOf(final UTF8String target, final UTF8String pattern,
      final int start, final int collationId) {
    if (pattern.numBytes() == 0) return target.indexOfEmpty(start);
    if (target.numBytes() == 0) return MATCH_NOT_FOUND;
    // Initialize the string search with respect to the specified ICU collation.
    String targetStr = target.toValidString();
    String patternStr = pattern.toValidString();
    StringSearch stringSearch =
      CollationFactory.getStringSearch(targetStr, patternStr, collationId);
    stringSearch.setOverlapping(true);
    // Start the search from `start`-th code point (NOT necessarily from the `start`-th character).
    int startIndex = targetStr.offsetByCodePoints(0, start);
    stringSearch.setIndex(startIndex);
    // Perform the search and return the next result, starting from the specified position.
    int searchIndex = stringSearch.next();
    if (searchIndex == StringSearch.DONE) {
      return MATCH_NOT_FOUND;
    }
    // Convert the search index from character count to code point count.
    int indexOf = targetStr.codePointCount(0, searchIndex);
    if (indexOf < start) {
      return MATCH_NOT_FOUND;
    }
    return indexOf;
  }

  private static int findIndex(final StringSearch stringSearch, int count) {
    assert(count >= 0);
    int index = 0;
    while (count > 0) {
      int nextIndex = stringSearch.next();
      if (nextIndex == StringSearch.DONE) {
        return MATCH_NOT_FOUND;
      } else if (nextIndex == index && index != 0) {
        stringSearch.setIndex(stringSearch.getIndex() + stringSearch.getMatchLength());
      } else {
        count--;
        index = nextIndex;
      }
    }
    return index;
  }

  private static int findIndexReverse(final StringSearch stringSearch, int count) {
    assert(count >= 0);
    int index = 0;
    while (count > 0) {
      index = stringSearch.previous();
      if (index == StringSearch.DONE) {
        return MATCH_NOT_FOUND;
      }
      count--;
    }
    return index + stringSearch.getMatchLength();
  }

  public static UTF8String subStringIndex(final UTF8String string, final UTF8String delimiter,
      int count, final int collationId) {
    if (delimiter.numBytes() == 0 || count == 0 || string.numBytes() == 0) {
      return UTF8String.EMPTY_UTF8;
    }
    String str = string.toValidString();
    String delim = delimiter.toValidString();
    StringSearch stringSearch = CollationFactory.getStringSearch(str, delim, collationId);
    stringSearch.setOverlapping(true);
    if (count > 0) {
      // If the count is positive, we search for the count-th delimiter from the left.
      int searchIndex = findIndex(stringSearch, count);
      if (searchIndex == MATCH_NOT_FOUND) {
        return string;
      } else if (searchIndex == 0) {
        return UTF8String.EMPTY_UTF8;
      } else {
        return UTF8String.fromString(str.substring(0, searchIndex));
      }
    } else {
      // If the count is negative, we search for the count-th delimiter from the right.
      int searchIndex = findIndexReverse(stringSearch, -count);
      if (searchIndex == MATCH_NOT_FOUND) {
          return string;
      } else if (searchIndex == str.length()) {
          return UTF8String.EMPTY_UTF8;
      } else {
          return UTF8String.fromString(str.substring(searchIndex));
      }
    }
  }

  public static UTF8String lowercaseSubStringIndex(final UTF8String string,
      final UTF8String delimiter, int count) {
    if (delimiter.numBytes() == 0 || count == 0) {
      return UTF8String.EMPTY_UTF8;
    }

    UTF8String lowercaseDelimiter = lowerCaseCodePoints(delimiter);

    if (count > 0) {
      // Search left to right (note: the start code point is inclusive).
      int matchLength = -1;
      while (count > 0) {
        matchLength = lowercaseFind(string, lowercaseDelimiter, matchLength + 1);
        if (matchLength > MATCH_NOT_FOUND) --count; // Found a delimiter.
        else return string; // Cannot find enough delimiters in the string.
      }
      return string.substring(0, matchLength);
    } else {
      // Search right to left (note: the end code point is exclusive).
      int matchLength = string.numChars() + 1;
      count = -count;
      while (count > 0) {
        matchLength = lowercaseRFind(string, lowercaseDelimiter, matchLength - 1);
        if (matchLength > MATCH_NOT_FOUND) --count; // Found a delimiter.
        else return string; // Cannot find enough delimiters in the string.
      }
      return string.substring(matchLength, string.numChars());
    }
  }

  /**
   * Converts the original translation dictionary (`dict`) to a dictionary with lowercased keys.
   * This method is used to create a dictionary that can be used for the UTF8_LCASE collation.
   * Note that `StringTranslate.buildDict` will ensure that all strings are validated properly.
   *
   * The method returns a map with lowercased code points as keys, while the values remain
   * unchanged. Note that `dict` is constructed on a character by character basis, and the
   * original keys are stored as strings. Keys in the resulting lowercase dictionary are stored
   * as integers, which correspond only to single characters from the original `dict`. Also,
   * there is special handling for the Turkish dotted uppercase letter I (U+0130).
   */
  private static Map<Integer, String> getLowercaseDict(final Map<String, String> dict) {
    // Replace all the keys in the dict with lowercased code points.
    Map<Integer, String> lowercaseDict = new HashMap<>();
    for (Map.Entry<String, String> entry : dict.entrySet()) {
      int codePoint = entry.getKey().codePointAt(0);
      lowercaseDict.putIfAbsent(getLowercaseCodePoint(codePoint), entry.getValue());
    }
    return lowercaseDict;
  }

  /**
   * Translates the `input` string using the translation map `dict`, for UTF8_LCASE collation.
   * String translation is performed by iterating over the input string, from left to right, and
   * repeatedly translating the longest possible substring that matches a key in the dictionary.
   * For UTF8_LCASE, the method uses the lowercased substring to perform the lookup in the
   * lowercased version of the translation map.
   *
   * @param input the string to be translated
   * @param dict the lowercase translation dictionary
   * @return the translated string
   */
  public static UTF8String lowercaseTranslate(final UTF8String input,
      final Map<String, String> dict) {
    // Iterator for the input string.
    Iterator<Integer> inputIter = input.codePointIterator(
      CodePointIteratorType.CODE_POINT_ITERATOR_MAKE_VALID);
    // Lowercased translation dictionary.
    Map<Integer, String> lowercaseDict = getLowercaseDict(dict);
    // StringBuilder to store the translated string.
    StringBuilder sb = new StringBuilder();

    // We use buffered code point iteration to handle one-to-many case mappings. We need to handle
    // at most two code points at a time (for `CODE_POINT_COMBINED_LOWERCASE_I_DOT`), a buffer of
    // size 1 enables us to match two codepoints in the input string with a single codepoint in
    // the lowercase translation dictionary.
    int codePointBuffer = -1, codePoint;
    while (inputIter.hasNext()) {
      if (codePointBuffer != -1) {
        codePoint = codePointBuffer;
        codePointBuffer = -1;
      } else {
        codePoint = inputIter.next();
      }
      // Special handling for letter i (U+0069) followed by a combining dot (U+0307). By ensuring
      // that `CODE_POINT_LOWERCASE_I` is buffered, we guarantee finding a max-length match.
      if (lowercaseDict.containsKey(CODE_POINT_COMBINED_LOWERCASE_I_DOT) &&
          codePoint == CODE_POINT_LOWERCASE_I && inputIter.hasNext()) {
        int nextCodePoint = inputIter.next();
        if (nextCodePoint == CODE_POINT_COMBINING_DOT) {
          codePoint = CODE_POINT_COMBINED_LOWERCASE_I_DOT;
        } else {
          codePointBuffer = nextCodePoint;
        }
      }
      // Translate the code point using the lowercased dictionary.
      String translated = lowercaseDict.get(getLowercaseCodePoint(codePoint));
      if (translated == null) {
        // Append the original code point if no translation is found.
        sb.appendCodePoint(codePoint);
      } else if (!"\0".equals(translated)) {
        // Append the translated code point if the translation is not the null character.
        sb.append(translated);
      }
      // Skip the code point if it maps to the null character.
    }
    // Append the last code point if it was buffered.
    if (codePointBuffer != -1) sb.appendCodePoint(codePointBuffer);

    // Return the translated string.
    return UTF8String.fromString(sb.toString());
  }

  /**
   * Translates the `input` string using the translation map `dict`, for all ICU collations.
   * String translation is performed by iterating over the input string, from left to right, and
   * repeatedly translating the longest possible substring that matches a key in the dictionary.
   * For ICU collations, the method uses the ICU `StringSearch` class to perform the lookup in
   * the translation map, while respecting the rules of the specified ICU collation.
   *
   * @param input the string to be translated
   * @param dict the collation aware translation dictionary
   * @param collationId the collation ID to use for string translation
   * @return the translated string
   */
  public static UTF8String translate(final UTF8String input,
      final Map<String, String> dict, final int collationId) {
    // Replace invalid UTF-8 sequences with the Unicode replacement character U+FFFD.
    String inputString = input.toValidString();
    // Create a character iterator for the validated input string. This will be used for searching
    // inside the string using ICU `StringSearch` class. We only need to do it once before the
    // main loop of the translate algorithm.
    CharacterIterator target = new StringCharacterIterator(inputString);
    Collator collator = CollationFactory.fetchCollation(collationId).collator;
    StringBuilder sb = new StringBuilder();
    // Index for the current character in the (validated) input string. This is the character we
    // want to determine if we need to replace or not.
    int charIndex = 0;
    while (charIndex < inputString.length()) {
      // We search the replacement dictionary to find a match. If there are more than one matches
      // (which is possible for collated strings), we want to choose the match of largest length.
      int longestMatchLen = 0;
      String longestMatch = "";
      for (String key : dict.keySet()) {
        StringSearch stringSearch = new StringSearch(key, target, (RuleBasedCollator) collator);
        // Point `stringSearch` to start at the current character.
        stringSearch.setIndex(charIndex);
        int matchIndex = stringSearch.next();
        if (matchIndex == charIndex) {
          // We have found a match (that is the current position matches with one of the characters
          // in the dictionary). However, there might be other matches of larger length, so we need
          // to continue searching against the characters in the dictionary and keep track of the
          // match of largest length.
          int matchLen = stringSearch.getMatchLength();
          if (matchLen > longestMatchLen) {
            longestMatchLen = matchLen;
            longestMatch = key;
          }
        }
      }
      if (longestMatchLen == 0) {
        // No match was found, so output the current character.
        sb.append(inputString.charAt(charIndex));
        // Move on to the next character in the input string.
        ++charIndex;
      } else {
        // We have found at least one match. Append the match of longest match length to the output.
        if (!"\0".equals(dict.get(longestMatch))) {
          sb.append(dict.get(longestMatch));
        }
        // Skip as many characters as the longest match.
        charIndex += longestMatchLen;
      }
    }
    // Return the translated string.
    return UTF8String.fromString(sb.toString());
  }

  /**
   * Trims the `srcString` string from both ends of the string using the specified `trimString`
   * characters, with respect to the UTF8_LCASE collation. String trimming is performed by
   * first trimming the left side of the string, and then trimming the right side of the string.
   * The method returns the trimmed string. If the `trimString` is null, the method returns null.
   *
   * @param srcString the input string to be trimmed from both ends of the string
   * @param trimString the trim string characters to trim
   * @return the trimmed string (for UTF8_LCASE collation)
   */
  public static UTF8String lowercaseTrim(
      final UTF8String srcString,
      final UTF8String trimString) {
    return lowercaseTrimRight(lowercaseTrimLeft(srcString, trimString), trimString);
  }

  /**
   * Trims the `srcString` string from both ends of the string using the specified `trimString`
   * characters, with respect to all ICU collations in Spark. String trimming is performed by
   * first trimming the left side of the string, and then trimming the right side of the string.
   * The method returns the trimmed string. If the `trimString` is null, the method returns null.
   *
   * @param srcString the input string to be trimmed from both ends of the string
   * @param trimString the trim string characters to trim
   * @param collationId the collation ID to use for string trimming
   * @return the trimmed string (for ICU collations)
   */
  public static UTF8String trim(
      final UTF8String srcString,
      final UTF8String trimString,
      final int collationId) {
    return trimRight(trimLeft(srcString, trimString, collationId), trimString, collationId);
  }

  /**
   * Trims the `srcString` string from the left side using the specified `trimString` characters,
   * with respect to the UTF8_LCASE collation. For UTF8_LCASE, the method first creates a hash
   * set of lowercased code points in `trimString`, and then iterates over the `srcString` from
   * the left side, until reaching a character whose lowercased code point is not in the hash set.
   * Finally, the method returns the substring from that position to the end of `srcString`.
   * If `trimString` is null, null is returned. If `trimString` is empty, `srcString` is returned.
   *
   * @param srcString the input string to be trimmed from the left end of the string
   * @param trimString the trim string characters to trim
   * @return the trimmed string (for UTF8_LCASE collation)
   */
  public static UTF8String lowercaseTrimLeft(
      final UTF8String srcString,
      final UTF8String trimString) {
    // Matching the default UTF8String behavior for null `trimString`.
    if (trimString == null) {
      return null;
    }

    // Create a hash set of lowercased code points for all characters of `trimString`.
    HashSet<Integer> trimChars = new HashSet<>();
    Iterator<Integer> trimIter = trimString.codePointIterator();
    while (trimIter.hasNext()) trimChars.add(getLowercaseCodePoint(trimIter.next()));

    // Iterate over `srcString` from the left to find the first character that is not in the set.
    int searchIndex = 0, codePoint;
    Iterator<Integer> srcIter = srcString.codePointIterator();
    while (srcIter.hasNext()) {
      codePoint = getLowercaseCodePoint(srcIter.next());
      // Special handling for Turkish dotted uppercase letter I.
      if (codePoint == CODE_POINT_LOWERCASE_I && srcIter.hasNext() &&
          trimChars.contains(CODE_POINT_COMBINED_LOWERCASE_I_DOT)) {
        int nextCodePoint = getLowercaseCodePoint(srcIter.next());
        if ((trimChars.contains(codePoint) && trimChars.contains(nextCodePoint))
          || nextCodePoint == CODE_POINT_COMBINING_DOT) {
          searchIndex += 2;
        }
        else {
          if (trimChars.contains(codePoint)) ++searchIndex;
          break;
        }
      } else if (trimChars.contains(codePoint)) {
        ++searchIndex;
      }
      else {
        break;
      }
    }

    // Return the substring from that position to the end of the string.
    return searchIndex == 0 ? srcString : srcString.substring(searchIndex, srcString.numChars());
  }

  /**
   * Trims the `srcString` string from the left side using the specified `trimString` characters,
   * with respect to ICU collations. For these collations, the method iterates over `srcString`
   * from left to right, and repeatedly skips the longest possible substring that matches any
   * character in `trimString`, until reaching a character that is not found in `trimString`.
   * Finally, the method returns the substring from that position to the end of `srcString`.
   * If `trimString` is null, null is returned. If `trimString` is empty, `srcString` is returned.
   *
   * @param srcString the input string to be trimmed from the left end of the string
   * @param trimString the trim string characters to trim
   * @param collationId the collation ID to use for string trimming
   * @return the trimmed string (for ICU collations)
   */
  public static UTF8String trimLeft(
      final UTF8String srcString,
      final UTF8String trimString,
      final int collationId) {
    // Short-circuit for base cases.
    if (trimString == null) return null;
    if (srcString.numBytes() == 0) return srcString;

    // Create an array of Strings for all characters of `trimString`.
    Map<Integer, String> trimChars = new HashMap<>();
    Iterator<Integer> trimIter = trimString.codePointIterator(
      CodePointIteratorType.CODE_POINT_ITERATOR_MAKE_VALID);
    while (trimIter.hasNext()) {
      int codePoint = trimIter.next();
      trimChars.putIfAbsent(codePoint, new String(Character.toChars(codePoint)));
    }

    // Iterate over srcString from the left and find the first character that is not in trimChars.
    String src = srcString.toValidString();
    CharacterIterator target = new StringCharacterIterator(src);
    Collator collator = CollationFactory.fetchCollation(collationId).collator;
    int charIndex = 0, longestMatchLen;
    while (charIndex < src.length()) {
      longestMatchLen = 0;
      for (String trim : trimChars.values()) {
        StringSearch stringSearch = new StringSearch(trim, target, (RuleBasedCollator) collator);
        stringSearch.setIndex(charIndex);
        int matchIndex = stringSearch.next();
        if (matchIndex == charIndex) {
          int matchLen = stringSearch.getMatchLength();
          if (matchLen > longestMatchLen) {
            longestMatchLen = matchLen;
          }
        }
      }
      if (longestMatchLen == 0) break;
      else charIndex += longestMatchLen;
    }

    // Return the substring from the calculated position until the end of the string.
    return UTF8String.fromString(src.substring(charIndex));
  }

  /**
   * Trims the `srcString` string from the right side using the specified `trimString` characters,
   * with respect to the UTF8_LCASE collation. For UTF8_LCASE, the method first creates a hash
   * set of lowercased code points in `trimString`, and then iterates over the `srcString` from
   * the right side, until reaching a character whose lowercased code point is not in the hash set.
   * Finally, the method returns the substring from the start of `srcString` until that position.
   * If `trimString` is null, null is returned. If `trimString` is empty, `srcString` is returned.
   *
   * @param srcString the input string to be trimmed from the right end of the string
   * @param trimString the trim string characters to trim
   * @return the trimmed string (for UTF8_LCASE collation)
   */
  public static UTF8String lowercaseTrimRight(
      final UTF8String srcString,
      final UTF8String trimString) {
    // Matching the default UTF8String behavior for null `trimString`.
    if (trimString == null) {
      return null;
    }

    // Create a hash set of lowercased code points for all characters of `trimString`.
    HashSet<Integer> trimChars = new HashSet<>();
    Iterator<Integer> trimIter = trimString.codePointIterator();
    while (trimIter.hasNext()) trimChars.add(getLowercaseCodePoint(trimIter.next()));

    // Iterate over `srcString` from the right to find the first character that is not in the set.
    int searchIndex = srcString.numChars(), codePoint;
    Iterator<Integer> srcIter = srcString.reverseCodePointIterator();
    while (srcIter.hasNext()) {
      codePoint = getLowercaseCodePoint(srcIter.next());
      // Special handling for Turkish dotted uppercase letter I.
      if (codePoint == CODE_POINT_COMBINING_DOT && srcIter.hasNext() &&
          trimChars.contains(CODE_POINT_COMBINED_LOWERCASE_I_DOT)) {
        int nextCodePoint = getLowercaseCodePoint(srcIter.next());
        if ((trimChars.contains(codePoint) && trimChars.contains(nextCodePoint))
          || nextCodePoint == CODE_POINT_LOWERCASE_I) {
          searchIndex -= 2;
        }
        else {
          if (trimChars.contains(codePoint)) --searchIndex;
          break;
        }
      } else if (trimChars.contains(codePoint)) {
        --searchIndex;
      }
      else {
        break;
      }
    }

    // Return the substring from the start of the string to the calculated position.
    return searchIndex == srcString.numChars() ? srcString : srcString.substring(0, searchIndex);
  }

  /**
   * Trims the `srcString` string from the right side using the specified `trimString` characters,
   * with respect to ICU collations. For these collations, the method iterates over `srcString`
   * from right to left, and repeatedly skips the longest possible substring that matches any
   * character in `trimString`, until reaching a character that is not found in `trimString`.
   * Finally, the method returns the substring from the start of `srcString` until that position.
   * If `trimString` is null, null is returned. If `trimString` is empty, `srcString` is returned.
   *
   * @param srcString the input string to be trimmed from the right end of the string
   * @param trimString the trim string characters to trim
   * @param collationId the collation ID to use for string trimming
   * @return the trimmed string (for ICU collations)
   */
  public static UTF8String trimRight(
      final UTF8String srcString,
      final UTF8String trimString,
      final int collationId) {
    // Short-circuit for base cases.
    if (trimString == null) return null;
    if (srcString.numBytes() == 0) return srcString;

    // Create an array of Strings for all characters of `trimString`.
    Map<Integer, String> trimChars = new HashMap<>();
    Iterator<Integer> trimIter = trimString.codePointIterator(
      CodePointIteratorType.CODE_POINT_ITERATOR_MAKE_VALID);
    while (trimIter.hasNext()) {
      int codePoint = trimIter.next();
      trimChars.putIfAbsent(codePoint, new String(Character.toChars(codePoint)));
    }

    // Iterate over srcString from the left and find the first character that is not in trimChars.
    String src = srcString.toValidString();
    CharacterIterator target = new StringCharacterIterator(src);
    Collator collator = CollationFactory.fetchCollation(collationId).collator;
    int charIndex = src.length(), longestMatchLen;
    while (charIndex >= 0) {
      longestMatchLen = 0;
      for (String trim : trimChars.values()) {
        StringSearch stringSearch = new StringSearch(trim, target, (RuleBasedCollator) collator);
        // Note: stringSearch.previous() is NOT consistent with stringSearch.next()!
        //  Example: StringSearch("İ", "i\\u0307İi\\u0307İi\\u0307İ", "UNICODE_CI")
        //    stringSearch.next() gives: [0, 2, 3, 5, 6, 8].
        //    stringSearch.previous() gives: [8, 6, 3, 0].
        // Since 1 character can map to at most 3 characters in Unicode, we can begin the search
        // from character position: `charIndex` - 3, and use `next()` to find the longest match.
        stringSearch.setIndex(Math.max(charIndex - 3, 0));
        int matchIndex = stringSearch.next();
        int matchLen = stringSearch.getMatchLength();
        while (matchIndex != StringSearch.DONE && matchIndex < charIndex - matchLen) {
          matchIndex = stringSearch.next();
          matchLen = stringSearch.getMatchLength();
        }
        if (matchIndex == charIndex - matchLen) {
          if (matchLen > longestMatchLen) {
            longestMatchLen = matchLen;
          }
        }
      }
      if (longestMatchLen == 0) break;
      else charIndex -= longestMatchLen;
    }

    // Return the substring from the start of the string until that position.
    return UTF8String.fromString(src.substring(0, charIndex));
  }

  public static UTF8String[] splitSQL(final UTF8String input, final UTF8String delim,
      final int limit, final int collationId) {
    if (CollationFactory.fetchCollation(collationId).supportsBinaryEquality) {
      return input.split(delim, limit);
    } else if (CollationFactory.fetchCollation(collationId).supportsLowercaseEquality) {
      return lowercaseSplitSQL(input, delim, limit);
    } else {
      return icuSplitSQL(input, delim, limit, collationId);
    }
  }

  public static UTF8String[] lowercaseSplitSQL(final UTF8String string, final UTF8String delimiter,
      final int limit) {
    if (delimiter.numBytes() == 0) return new UTF8String[] { string };
    if (string.numBytes() == 0) return new UTF8String[] { UTF8String.EMPTY_UTF8 };

    List<UTF8String> strings = new ArrayList<>();
    UTF8String lowercaseDelimiter = lowerCaseCodePoints(delimiter);
    int startIndex = 0, nextMatch = 0, nextMatchLength;
    while (nextMatch != MATCH_NOT_FOUND) {
      if (limit > 0 && strings.size() == limit - 1) {
        break;
      }
      nextMatch = lowercaseFind(string, lowercaseDelimiter, startIndex);
      if (nextMatch != MATCH_NOT_FOUND) {
        nextMatchLength = lowercaseMatchLengthFrom(string, lowercaseDelimiter, nextMatch);
        strings.add(string.substring(startIndex, nextMatch));
        startIndex = nextMatch + nextMatchLength;
      }
    }
    if (startIndex <= string.numChars()) {
      strings.add(string.substring(startIndex, string.numChars()));
    }
    if (limit == 0) {
      // Remove trailing empty strings
      int i = strings.size() - 1;
      while (i >= 0 && strings.get(i).numBytes() == 0) {
        strings.remove(i);
        i--;
      }
    }
    return strings.toArray(new UTF8String[0]);
  }

  public static UTF8String[] icuSplitSQL(final UTF8String string, final UTF8String delimiter,
      final int limit, final int collationId) {
    if (delimiter.numBytes() == 0) return new UTF8String[] { string };
    if (string.numBytes() == 0) return new UTF8String[] { UTF8String.EMPTY_UTF8 };
    List<UTF8String> strings = new ArrayList<>();
    String target = string.toValidString(), pattern = delimiter.toValidString();
    StringSearch stringSearch = CollationFactory.getStringSearch(target, pattern, collationId);
    int start = 0, end;
    while ((end = stringSearch.next()) != StringSearch.DONE) {
      if (limit > 0 && strings.size() == limit - 1) {
        break;
      }
      strings.add(UTF8String.fromString(target.substring(start, end)));
      start = end + stringSearch.getMatchLength();
    }
    if (start <= target.length()) {
      strings.add(UTF8String.fromString(target.substring(start)));
    }
    if (limit == 0) {
      // Remove trailing empty strings
      int i = strings.size() - 1;
      while (i >= 0 && strings.get(i).numBytes() == 0) {
        strings.remove(i);
        i--;
      }
    }
    return strings.toArray(new UTF8String[0]);
  }

  /**
   * Title casing a string according to a new behaviour.
   * Iterates over the string and title cases the first character in each word, and lowercases
   * every other character.
   * Handles lowercasing greek sigma(Σ) separately, taking into account if
   * it should be a small final sigma(ς) or small non-final sigma(σ).
   * Words are separated by ASCII space(\u0020).
   *
   * @param target UTF8String to be title cased
   * @return title cased target
   */
  public static UTF8String toTitleCaseICU(UTF8String target) {

    Iterator<Integer> codepointIterator = target.
            codePointIterator(CodePointIteratorType.CODE_POINT_ITERATOR_MAKE_VALID);

    // building the title cased target with sb
    StringBuilder sb=new StringBuilder();
    // newWord is true if the current character is the beginning of a word, false otherwise
    // it is true on the i-th character if i==0 or target at (i-1)-th character is ' '
    boolean newWord = true;
    // we are maintaining if the current character is preceded by a cased letter
    // this is used when lowercasing Σ, to figure out if it should be lowercased into σ or ς
    boolean precededByCasedLetter = false;

    // byte offset in target's byte array pointing to the beginning of the character that we
    // need to process next(this is only actually used in appendLowerCasedGreekCapitalSigma)
    int offset = 0;

    while(codepointIterator.hasNext()) {
      int codepoint = codepointIterator.next();
      // appending the correctly cased character onto sb
      appendTitleCasedCodepoint(sb, codepoint, newWord, precededByCasedLetter, target, offset);
      // updating newWord, precededByCasedLetter and offset to be ready for
      // the next character that we will process
      newWord = (codepoint == ASCII_SPACE_CODEPOINT);
      if(!UCharacter.hasBinaryProperty(codepoint, UProperty.CASE_IGNORABLE)){
        precededByCasedLetter = UCharacter.hasBinaryProperty(codepoint, UProperty.CASED);
      }
      offset+= UTF8String.numBytesForFirstByte(target.getByte(offset));
    }
    return UTF8String.fromString(sb.toString());
  }

  private static void appendTitleCasedCodepoint(StringBuilder sb, int codepoint,
                                                boolean isAfterAsciiSpace,
                                                boolean precededByCasedLetter, UTF8String target,
                                                int offset) {
    if(isAfterAsciiSpace) {
      // titlecasing a character if it is in the beginning of a new word
      sb.append(codepointToTitleString(codepoint));
      return;
    }
    if(codepoint == CAPITAL_SIGMA) {
      // handling Σ separately
      appendLowerCasedGreekCapitalSigma(sb, precededByCasedLetter, target, offset);
      return;
    }
    // if it's not the beginning of a word, or a Σ, we lowercase the character
    sb.append(toLowerCase(UTF8String.fromString(new String(Character.toChars(codepoint)))).
            toString());
  }

  private static void appendLowerCasedGreekCapitalSigma(StringBuilder sb,
                                                        boolean precededByCasedLetter,
                                                        UTF8String target, int offset) {
    int codepoint = (!followedByCasedLetter(target,offset) && precededByCasedLetter)?
            SMALL_FINAL_SIGMA : SMALL_NON_FINAL_SIGMA;
    sb.appendCodePoint(codepoint);
  }

  /**
   * Checks if the character beginning at offset(in targets byte array) is followed
   * by a cased letter
   *
   * @param target
   * @param offset
   * @return
   */
  private static boolean followedByCasedLetter(UTF8String target, int offset) {
    // moving the offset one character forward, so we could start our linear search from there
    offset+= UTF8String.numBytesForFirstByte(target.getByte(offset));
    int len = target.numBytes();

    while(offset < len) {

      int codepoint = target.codePointFrom(offset);

      if(UCharacter.hasBinaryProperty(codepoint, UProperty.CASE_IGNORABLE)) {
        offset += UTF8String.numBytesForFirstByte(target.getByte(offset));
        continue;
      }
      return UCharacter.hasBinaryProperty(codepoint, UProperty.CASED);
    }
    return false;
  }

  /**
   * Titlecases a single character using the ICU root locale rules.
   *
   * @param target is a character that is needed to be title cased
   * @return a java String(whose length can be more than 1 character)
   * which corresponds to the title case of the codepoint
   */
  private static String codepointToTitleString(int codepoint) {
    return UCharacter.toTitleCase(new String(Character.toChars(codepoint)),null);
  }

  private static final int ASCII_SPACE_CODEPOINT = 32;
  private static final int CAPITAL_SIGMA = 0x03A3;
  private static final int SMALL_NON_FINAL_SIGMA = 0x03C3;
  private static final int SMALL_FINAL_SIGMA = 0x03C2;

  // TODO: Add more collation-aware UTF8String operations here.

}
