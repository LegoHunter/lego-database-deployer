package net.lego.database.deployer.config;

import java.util.Comparator;

/**
 * The order in which {@code includeAll} applies the changelog files: by version number, not alphabetically.
 *
 * <p>Files are named {@code <version>-<slug>.yaml}. Liquibase's own order compares the names as text, which puts
 * {@code 2.10.0} between {@code 2.1.0} and {@code 2.2.0}. This order compares each run of digits as a number, so
 * {@code 2.10.0} follows {@code 2.9.0}. Everything that is not a digit is compared as text, as before.
 *
 * <p>For the files up to {@code 2.9.0} the two orders are the same, so adopting this one changed nothing that had
 * already run. Liquibase names this class in each master changelog's {@code resourceComparator}.
 */
public class ChangelogFileOrder implements Comparator<String> {

    @Override
    public int compare(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            char a = left.charAt(i);
            char b = right.charAt(j);
            if (Character.isDigit(a) && Character.isDigit(b)) {
                int endOfLeft = endOfDigits(left, i);
                int endOfRight = endOfDigits(right, j);
                int byNumber = compareNumbers(left.substring(i, endOfLeft), right.substring(j, endOfRight));
                if (byNumber != 0) {
                    return byNumber;
                }
                i = endOfLeft;
                j = endOfRight;
            } else {
                if (a != b) {
                    return Character.compare(a, b);
                }
                i++;
                j++;
            }
        }
        return Integer.compare(left.length() - i, right.length() - j);
    }

    private static int endOfDigits(String text, int from) {
        int end = from;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        return end;
    }

    /** Compares two runs of digits by value, whatever their length; equal values fall back to the text. */
    private static int compareNumbers(String left, String right) {
        String a = stripLeadingZeros(left);
        String b = stripLeadingZeros(right);
        if (a.length() != b.length()) {
            return Integer.compare(a.length(), b.length());
        }
        int byDigits = a.compareTo(b);
        return byDigits != 0 ? byDigits : left.compareTo(right);
    }

    private static String stripLeadingZeros(String digits) {
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') {
            start++;
        }
        return digits.substring(start);
    }
}
