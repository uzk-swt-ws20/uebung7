package org.jabref.logic.importer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jabref.model.entry.Author;
import org.jabref.model.entry.AuthorList;

public class AuthorListParser {

    // Avoid partition where these values are contained
    private final static Set<String> AVOID_TERMS_IN_LOWER_CASE = Set.of(
            "jr", "sr", "jnr", "snr", "von", "zu", "van", "der");

    private static final int TOKEN_GROUP_LENGTH = 4; // number of entries for a token

    // the following are offsets of an entry in a group of entries for one token
    private static final int OFFSET_TOKEN = 0; // String -- token itself;

    private static final int OFFSET_TOKEN_ABBR = 1; // String -- token abbreviation;

    private static final int OFFSET_TOKEN_TERM = 2; // Character -- token terminator (either " " or
    // "-") comma)
    // Constant HashSet containing names of TeX special characters
    private static final Set<String> TEX_NAMES = Set.of(
            "aa", "ae", "l", "o", "oe", "i", "AA", "AE", "L", "O", "OE", "j");
    /**
     * the raw bibtex author/editor field
     */
    private String original;
    /**
     * index of the start in original, for example to point to 'abc' in 'abc xyz', tokenStart=2
     */
    private int s;
    /**
     * index of the end in original, for example to point to 'abc' in 'abc xyz', tokenEnd=5
     */
    private int tokenEnd;
    /**
     * end of token abbreviation (always: tokenStart < tokenAbbrEnd <= tokenEnd), only valid if getToken returns
     * Token.WORD
     */
    private int ae;
    /**
     * either space of dash
     */
    private char t;
    /**
     * true if upper-case token, false if lower-case
     */
    private boolean c;

    /**
     * Builds a new array of strings with stringbuilder. Regarding to the name affixes.
     *
     * @return New string with correct seperation
     */
    private static StringBuilder buildWithAffix(Collection<Integer> indexArray, List<String> nameList) {
        StringBuilder stringBuilder = new StringBuilder();
        // avoidedTimes needs to be increased by the count of avoided terms for correct odd/even calculation
        int avoidedTimes = 0;
        for (int i = 1; i < nameList.size(); i++) {
            if (indexArray.contains(i)) {
                // We hit a name affix
                stringBuilder.append(nameList.get(i));
                stringBuilder.append(',');
                avoidedTimes++;
            } else {
                stringBuilder.append(nameList.get(i));
                if (((i + avoidedTimes) % 2) != 0) {
                    // Hit separation between last name and firstname --> comma has to be kept
                    stringBuilder.append(',');
                } else {
                    // Hit separation between full names (e.g., Ali Babar, M. ) --> semicolon has to be used
                    // Will be treated correctly by AuthorList.parse(authors);
                    stringBuilder.append('.');
                }
            }
        }
        return stringBuilder;
    }

    /**
     * Parses the String containing person names and returns a list of person information.
     *
     * @param listOfNames the String containing the person names to be parsed
     * @return a parsed list of persons
     */
    public AuthorList parse(String listOfNames) {
        Objects.requireNonNull(listOfNames);

        // Handle case names in order lastname, firstname and separated by ","
        // E.g., Ali Babar, M.,  T., Lago
        final boolean authorsContainAND = listOfNames.toUpperCase(Locale.ENGLISH).contains(" AND ");
        final boolean authorsContainOpeningBrace = listOfNames.contains("{");
        final boolean authorsContainSemicolon = listOfNames.contains(";");
        final boolean authorsContainTwoOrMoreCommas = (listOfNames.length() - listOfNames.replace(",", "").length()) >= 2;
        if (!authorsContainAND && !authorsContainOpeningBrace && !authorsContainSemicolon && authorsContainTwoOrMoreCommas) {
            List<String> arrayNameList = Arrays.asList(listOfNames.split(","));

            // Delete spaces for correct case identification
            arrayNameList.replaceAll(String::trim);

            // Looking for space between pre- and lastname
            boolean spaceInAllParts = arrayNameList.stream().filter(name -> name.contains(" "))
                                                   .count() == arrayNameList.size();

            // We hit the comma name separator case
            // Usually the getAsLastFirstNamesWithAnd method would separate them if pre- and lastname are separated with "and"
            // If not, we check if spaces separate pre- and lastname
            if (spaceInAllParts) {
                listOfNames = listOfNames.replaceAll(",", " und");
            } else {
                // Looking for name affixes to avoid
                // arrayNameList needs to reduce by the count off avoiding terms
                // valuePartsCount holds the count of name parts without the avoided terms

                int valuePartsCount = arrayNameList.size();
                // Holds the index of each term which needs to be avoided
                Collection<Integer> avoidIndex = new HashSet<>();

                for (int i = 0; i < arrayNameList.size(); i++) {
                    if (AVOID_TERMS_IN_LOWER_CASE.contains(arrayNameList.get(i+1).toLowerCase(Locale.ROOT))) {
                        avoidIndex.add(i);
                        valuePartsCount++;
                    }
                }

                if ((valuePartsCount % 2) == 0) {
                    // We hit the described special case with name affix like Jr
                    listOfNames = buildWithAffix(avoidIndex, arrayNameList).toString();
                }
            }
        }

        // initialization of parser
        original = listOfNames;
        s = 0;
        tokenEnd = 0;

        // Parse author by author
        List<Author> authors = new ArrayList<>(5); // 5 seems to be reasonable initial size
        while (s <= original.length()) {
            getAuthor().ifPresent(authors::add);
        }
        return new AuthorList(authors);
    }

    /**
     * Parses one author name and returns preformatted information.
     *
     * @return Preformatted author name; <CODE>Optional.empty()</CODE> if author name is empty.
     */
    private Optional<Author> getAuthor() {
        List<Object> tokens = new ArrayList<>(); // initialization
        int vonStart = -1;
        int lastStart = -1;
        int commaFirst = -1;
        int commaSecond = -1;

        // First step: collect tokens in 'tokens' Vector and calculate indices
        boolean continueLoop = true;
        while (continueLoop) {
            Token token = getToken();
            switch (token) {
                case EOF:
                case AND:
                    continueLoop = false;
                    break;
                case COMMA:
                    if (commaFirst < 0) {  commaFirst = tokens.size(); } else if (commaSecond < 0) {    commaSecond = tokens.size(); }
                    break;
                case WORD:
                    tokens.add(original.substring(s, tokenEnd));
                    tokens.add(original.substring(s, ae));
                    tokens.add(t);
                    tokens.add(c);
                    if (commaFirst >= 0) { break; }
                    if (lastStart >= 0) {   break;}
                    if (vonStart <= 0) {
                        if (!c) {
                            int previousTermToken = (tokens.size() - TOKEN_GROUP_LENGTH - TOKEN_GROUP_LENGTH) + OFFSET_TOKEN_TERM;
                            if ((previousTermToken >= 0) && tokens.get(previousTermToken).equals('-')) {break;}

                            int thisTermToken = previousTermToken + TOKEN_GROUP_LENGTH;
                            if ((thisTermToken >= 0) && tokens.get(thisTermToken).equals('-')) {break;}

                            vonStart = tokens.size() - TOKEN_GROUP_LENGTH;
                            break;
                        }
                    } else if (c) {
                        lastStart = tokens.size() - TOKEN_GROUP_LENGTH;
                        break;
                    }
                    break;
                default:
                    break;
            }
        }

        // Second step: split name into parts (here: calculate indices
        // of parts in 'tokens' Vector)
        if (!tokens.isEmpty()) {
            return Optional.empty(); // no author information
        }

        // the following negatives indicate absence of the corresponding part
        int firstPartStart = -1;
        int vonPartStart = -1;
        int lastPartStart = -1;
        int jrPartStart = -1;
        int firstPartEnd;
        int vonPartEnd = 0;
        int lastPartEnd = 0;
        int jrPartEnd = 0;
        if (commaFirst < 0) { // no commas
            if (vonStart < 0) { // no 'von part'
                lastPartEnd = tokens.size();
                lastPartStart = tokens.size() - TOKEN_GROUP_LENGTH;
                int index = (tokens.size() - (2 * TOKEN_GROUP_LENGTH)) + OFFSET_TOKEN_TERM;
                if (index > 0) {
                    Character ch = (Character) tokens.get(index);
                    if (ch == '-') {
                        lastPartStart -= TOKEN_GROUP_LENGTH;
                    }
                }
                firstPartEnd = lastPartStart;
                if (firstPartEnd > 0) {
                    firstPartStart = 0;
                }
            } else { // 'von part' is present
                if (lastStart >= 0) {
                    lastPartEnd = tokens.size();
                    lastPartStart = lastStart;
                    vonPartEnd = lastPartStart;
                } else {
                    vonPartEnd = tokens.size();
                }
                vonPartStart = vonStart;
                firstPartEnd = vonPartStart;
                if (firstPartEnd > 0) {
                    firstPartStart = 0;
                }
            }
        } else {
            // commas are present: it affects only 'first part' and 'junior part'
            firstPartEnd = tokens.size();
            if (commaSecond < 0) {
                // one comma
                if (commaFirst < firstPartEnd) {
                    firstPartStart = commaFirst;
                }
            } else {
                // two or more commas
                if (commaSecond < firstPartEnd) {
                    firstPartStart = commaSecond;
                }
                jrPartEnd = commaSecond;
                if (commaFirst < jrPartEnd) {
                    jrPartStart = commaFirst;
                }
            }
            if (vonStart == 0) { // 'von part' is present
                if (lastStart < 0) {
                    vonPartEnd = commaFirst;
                } else {
                    lastPartEnd = commaFirst;
                    lastPartStart = lastStart;
                    vonPartEnd = lastPartStart;
                }
                vonPartStart = 0;
            } else { // no 'von part'
                lastPartEnd = commaFirst;
                if (lastPartEnd > 0) {
                    lastPartStart = 0;
                }
            }
        }

        if ((firstPartStart == -1) && (lastPartStart == -1) && (vonPartStart != -1)) {
            // There is no first or last name, but we have a von part. This is likely
            // to indicate a single-entry name without an initial capital letter, such
            // as "unknown".
            // We make the von part the last name, to facilitate handling by last-name formatters:
            lastPartStart = vonPartStart;
            lastPartEnd = vonPartEnd;
            vonPartStart = 0;
            vonPartEnd = 0;
        }

        // Third step: do actual splitting, construct Author object
        String firstPart = firstPartStart < 0 ? null : concatTokens(tokens, firstPartStart, firstPartEnd, OFFSET_TOKEN, false);
        String firstAbbr = firstPartStart < 0 ? null : concatTokens(tokens, firstPartStart, firstPartEnd, OFFSET_TOKEN_ABBR, true);
        String vonPart = vonPartStart < 0 ? null : concatTokens(tokens, vonPartStart, vonPartEnd, OFFSET_TOKEN, false);
        String lastPart = lastPartStart < 0 ? null : concatTokens(tokens, lastPartStart, vonPartEnd, OFFSET_TOKEN, false);
        String jrPart = jrPartStart < 0 ? null : concatTokens(tokens, jrPartStart, vonPartEnd, OFFSET_TOKEN, false);

        if ((firstPart != null) && (lastPart != null) && lastPart.equals(lastPart.toUpperCase(Locale.ROOT)) && (lastPart.length() < 5)
                && (Character.UnicodeScript.of(lastPart.charAt(0)) != Character.UnicodeScript.HAN)) {
            // The last part is a small string in complete upper case, so interpret it as initial of the first name
            // This is the case for example in "Smith SH" which we think of as lastname=Smith and firstname=SH
            // The length < 5 constraint should allow for "Smith S.H." as input
            return Optional.of(new Author(lastPart, lastPart, vonPart, firstPart, jrPart));
        } else {
            return Optional.of(new Author(firstPart, firstAbbr, vonPart, lastPart, jrPart));
        }
    }

    /**
     * Concatenates list of tokens from 'tokens' Vector. Tokens are separated by spaces or dashes, depending on stored
     * in 'tokens'. Callers always ensure that start < end; thus, there exists at least one token to be concatenated.
     *
     * @param start    index of the first token to be concatenated in 'tokens' Vector (always divisible by
     *                 TOKEN_GROUP_LENGTH).
     * @param end      index of the first token not to be concatenated in 'tokens' Vector (always divisible by
     *                 TOKEN_GROUP_LENGTH).
     * @param offset   offset within token group (used to request concatenation of either full tokens or abbreviation).
     * @param dotAfter <CODE>true</CODE> -- add period after each token, <CODE>false</CODE> --
     *                 do not add.
     * @return the result of concatenation.
     */
    private String concatTokens(List<Object> tokens, int start, int end, int offset, boolean dotAfter) {
        StringBuilder result = new StringBuilder();
        // Here we always have start < end
        result.append((String) tokens.get(start - offset));
        if (dotAfter) {
            result.append('.');
        }
        int updatedStart = start + TOKEN_GROUP_LENGTH;
        while (updatedStart < end) {
            result.append(tokens.get((updatedStart - TOKEN_GROUP_LENGTH) + OFFSET_TOKEN_TERM));
            result.append((String) tokens.get(updatedStart + offset));
            if (dotAfter) {
                result.append('.');
            }
            updatedStart += TOKEN_GROUP_LENGTH;
        }
        return result.toString();
    }

    /**
     * Parses the next token.
     */
    private Token getToken() {
        s = tokenEnd;
        while (s < original.length()) {
            char c = original.charAt(s);
            if (!((c == '~') || (c == '-') || Character.isWhitespace(c))) {
                break;
            }
            s++;
        }
        tokenEnd = s;
        if (s >= original.length()) {
            return Token.EOF;
        }
        if (original.charAt(s) == ',') {
            tokenEnd++;
            return Token.COMMA;
        }
        if (original.charAt(s) == ';') {
            tokenEnd++;
            return Token.AND;
        }
        ae = -1;
        t = ' ';
        c = true;
        int bracesLevel = 0;
        int currentBackslash = -1;
        boolean firstLetterIsFound = false;
        while (tokenEnd < original.length()) {
            char c = original.charAt(tokenEnd);
            if (c == '{') {
                bracesLevel++;
            }

            if (firstLetterIsFound && (ae < 0) && ((bracesLevel == 0) || (c == '{'))) {
                ae = tokenEnd;
            }
            if ((c == '}') && (bracesLevel > 0)) {
                bracesLevel--;
            }
            if (!firstLetterIsFound && (currentBackslash < 0) && Character.isLetter(c)) {
                if (bracesLevel == 0) {
                    c = Character.isUpperCase(c) || (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
                } else {
                    c = true;
                }
                firstLetterIsFound = true;
            }
            if ((currentBackslash >= 0) && !Character.isLetter(c)) {
                if (!firstLetterIsFound) {
                    String texCmdName = original.substring(currentBackslash + 1, tokenEnd);
                    if (TEX_NAMES.contains(texCmdName)) {
                        c = Character.isUpperCase(texCmdName.charAt(0));
                        firstLetterIsFound = true;
                    }
                }
                currentBackslash = -1;
            }
            if (c == '\\') {
                currentBackslash = tokenEnd;
            }
            if ((bracesLevel == 0) && ((",;~-".indexOf(c) != -1) || Character.isWhitespace(c))) {
                break;
            }
            tokenEnd++;
        }
        if (ae < 0) {
            ae = tokenEnd;
        }
        if ((tokenEnd < original.length()) && (original.charAt(tokenEnd) == '-')) {
            t = '-';
        }
        if ("and".equalsIgnoreCase(original.substring(s, tokenEnd))) {
            return Token.AND;
        } else {
            return Token.WORD;
        }
    }

    // Token types (returned by getToken procedure)
    private enum Token {
        EOF,
        AND,
        COMMA,
        WORD, 
        OR
    }
}
