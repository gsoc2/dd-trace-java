package datadog.trace.api.normalize;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;

/**
 * This class removes numbers and SQL literals from strings on a best-effort basis, producing UTF-8
 * encoded bytes. The aim is to remove as much information as possible, but only when it's cheap to
 * do so. It makes no context-sensitive decisions, which works well for ANSI SQL, but, for example,
 * will not remove literals in MySQL which are indistinguishable from object names. This is not an
 * obfuscator, and the strings produced by this class must be passed through obfuscation in the
 * trace agent.
 */
public final class SQLNormalizer {

  private static final long[] OBFUSCATE_SEQUENCES_STARTING_WITH = new long[4];
  private static final long[] NON_WHITESPACE_SPLITTERS = new long[4];

  static {
    for (byte symbol :
        new byte[] {'\'', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-', '+', '.'}) {
      int unsigned = symbol & 0xFF;
      OBFUSCATE_SEQUENCES_STARTING_WITH[unsigned >>> 6] |= (1L << unsigned);
    }
    for (byte symbol : new byte[] {',', '(', ')'}) {
      int unsigned = symbol & 0xFF;
      NON_WHITESPACE_SPLITTERS[unsigned >>> 6] |= (1L << unsigned);
    }
  }

  private static final long SPACES = pattern((byte) ' ');
  private static final long TABS = pattern((byte) '\t');
  private static final long NEW_LINES = pattern((byte) '\n');
  private static final long COMMAS = pattern((byte) ',');
  private static final long L_PAREN = pattern((byte) '(');
  private static final long R_PAREN = pattern((byte) ')');

  public static UTF8BytesString normalize(String sql) {
    byte[] utf8 = sql.getBytes(UTF_8);
    BitSet splitters = findTokenPositions(utf8);
    // no whitespace
    if (null == splitters) {
      return UTF8BytesString.create(sql, utf8);
    }
    int outputLength = utf8.length;
    int end = outputLength - 1;
    int start = splitters.previousSetBit(end - 1);
    // strip out anything ending with a quote (covers string and hex literals)
    // or anything starting with a number, a quote, a decimal point, or a sign
    while (end > 0) {
      if (start == end - 1 && utf8[end] != '\'') {
        // avoid an unnecessary array copy for one digit numbers
        if (utf8[end] >= '0' && utf8[end] <= '9') {
          utf8[end] = (byte) '?';
        }
      } else {
        int first = -1;
        int last = isNonWhitespaceSplitter(utf8[end]) ? end - 1 : end;
        // quote literals may span several splits
        if (utf8[end] == '\'') {
          while (start > -1) {
            // found the start of a string or hex literal
            if (start + 1 < end
                && (utf8[start + 1] == '\''
                    || (start + 1 < end - 1
                        && utf8[start + 1] != '\\'
                        && utf8[start + 2] == '\''))) {
              first = start + 1;
              break;
            }
            start = splitters.previousSetBit(start - 1);
          }
        } else if (start < end - 1
            && (utf8[end] == ')' || shouldReplaceSequenceStartingWith(utf8[start + 1]))) {
          first = start + 1;
        }
        // found something to remove, shift the suffix of the string backwards
        // and add the obfuscated character
        if (first > -1) {
          System.arraycopy(utf8, last, utf8, first, outputLength - last);
          utf8[first] = (byte) '?';
          outputLength -= (last - first);
        }
      }
      end = start - 1;
      start = end > 0 ? splitters.previousSetBit(end) : -1;
    }
    return UTF8BytesString.create(Arrays.copyOf(utf8, outputLength));
  }

  private static BitSet findTokenPositions(byte[] utf8) {
    int capacity = (utf8.length + 7) & -8;
    BitSet whitespace = new BitSet(capacity);
    int tokensFound = 0;
    int pos = 0;
    ByteBuffer buffer = ByteBuffer.wrap(utf8);
    for (; pos < (utf8.length & -8); pos += 8) {
      long word = buffer.getLong(pos);
      long tokens = findTokens(word);
      tokensFound += Long.bitCount(tokens);
      while (tokens != 0) {
        whitespace.set(pos + 7 - (Long.numberOfTrailingZeros(tokens) >>> 3));
        tokens &= (tokens - 1);
      }
    }
    if (pos < utf8.length && utf8.length >= 8) {
      long word = buffer.getLong(utf8.length - 8);
      word <<= ((8 - (utf8.length - pos)) << 3);
      long tokens = findTokens(word);
      tokensFound += Long.bitCount(tokens);
      while (tokens != 0) {
        whitespace.set(pos + 7 - (Long.numberOfTrailingZeros(tokens) >>> 3));
        tokens &= (tokens - 1);
      }
    } else if (pos < utf8.length) {
      for (int i = pos; i < utf8.length; ++i) {
        if (Character.isWhitespace((char) (utf8[i] & 0xFF)) || isNonWhitespaceSplitter(utf8[i])) {
          whitespace.set(i);
        }
      }
    }
    return tokensFound == 0 ? null : whitespace;
  }

  private static boolean shouldReplaceSequenceStartingWith(byte symbol) {
    return (OBFUSCATE_SEQUENCES_STARTING_WITH[(symbol & 0xFF) >>> 6] & (1L << (symbol & 0xFF)))
        != 0;
  }

  private static boolean isNonWhitespaceSplitter(byte symbol) {
    return (NON_WHITESPACE_SPLITTERS[(symbol & 0xFF) >>> 6] & (1L << (symbol & 0xFF))) != 0;
  }

  private static long findTokens(long word) {
    return tag(word, SPACES)
        | tag(word, TABS)
        | tag(word, NEW_LINES)
        | tag(word, L_PAREN)
        | tag(word, R_PAREN)
        | tag(word, COMMAS);
  }

  private static long tag(long pattern, long word) {
    word ^= pattern;
    long holes = (word & 0x7F7F7F7F7F7F7F7FL) + 0x7F7F7F7F7F7F7F7FL;
    return ~(holes | word | 0x7F7F7F7F7F7F7F7FL);
  }

  private static long pattern(byte symbol) {
    return (symbol & 0xFFL) * 0x101010101010101L;
  }
}
