package com.ultikits.ultitools.config.document;

import java.util.ArrayList;
import java.util.List;

/**
 * A minimal line diff for the storage-layer tests: the lines only in the old text and the lines only in the
 * new text, by longest common subsequence. Lines keep their terminators, so a changed line ending counts as a
 * changed line.
 */
final class LineDiff {

    final List<String> removed = new ArrayList<>();
    final List<String> added = new ArrayList<>();
    /** Line numbers (0-based, in the old text) of the removed lines. */
    final List<Integer> removedAt = new ArrayList<>();
    /** Line numbers (0-based, in the new text) of the added lines. */
    final List<Integer> addedAt = new ArrayList<>();

    private LineDiff() {
    }

    static LineDiff of(String before, String after) {
        List<String> a = lines(withoutByteOrderMark(before));
        List<String> b = lines(withoutByteOrderMark(after));
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        LineDiff diff = new LineDiff();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                diff.removedAt.add(i);
                diff.removed.add(a.get(i++));
            } else {
                diff.addedAt.add(j);
                diff.added.add(b.get(j++));
            }
        }
        while (i < a.size()) {
            diff.removedAt.add(i);
            diff.removed.add(a.get(i++));
        }
        while (j < b.size()) {
            diff.addedAt.add(j);
            diff.added.add(b.get(j++));
        }
        return diff;
    }

    /** Whether the removed lines are one contiguous block of the old text. */
    boolean removedIsContiguous() {
        return contiguous(removedAt);
    }

    /** Whether the added lines are one contiguous block of the new text. */
    boolean addedIsContiguous() {
        return contiguous(addedAt);
    }

    private static boolean contiguous(List<Integer> at) {
        for (int k = 1; k < at.size(); k++) {
            if (at.get(k) != at.get(k - 1) + 1) {
                return false;
            }
        }
        return true;
    }

    private static String withoutByteOrderMark(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** Splits after every line break ({@code \r\n}, {@code \n} or {@code \r}), keeping the break with its line. */
    static List<String> lines(String text) {
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                result.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            result.add(text.substring(start));
        }
        return result;
    }

    @Override
    public String toString() {
        return "removed=" + removed + ", added=" + added;
    }
}
