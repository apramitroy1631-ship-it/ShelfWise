package com.shelfwise.core.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The search-box logic, done the way shopping sites do it (Flipkart, Myntra): the typed text is split into words and
 * <b>every word must match, in any order</b> ("chips potato" finds "Potato Chips 150g"); a word matches anywhere in the
 * name, SKU or category, so half-typed words work ("pota chi"); and a word with a small typo still matches the name
 * ("potatoe", "shampo", "batary"). Results are ranked, not just filtered: exact SKU first, then names that start with
 * what was typed, then whole-word matches, then partial matches, then typo matches.
 * <p>
 * It is built on PostgreSQL's {@code pg_trgm} (typo tolerance + fast "contains" search), so there is no extra search
 * server to run. Search engines such as Solr/Elasticsearch do the same steps (split, normalise, match, correct, rank)
 * at a scale where one database table is no longer enough; if the catalogue ever grows to that point, this class is
 * the one place to swap out.
 */
public final class TextSearch {

    /** The most words we look at: beyond this a long pasted sentence would only slow the query down. */
    private static final int MAX_WORDS = 6;
    /** Typo matching starts at this word length; "chip" vs "chop" is too ambiguous to guess. */
    private static final int MIN_LENGTH_FOR_TYPOS = 4;
    /** How alike (0 to 1) a typed word and a word in the name must be. 0.5 allows one or two wrong letters in a 5+ letter word. */
    private static final double TYPO_SIMILARITY = 0.5;

    /**
     * {@code condition} goes in WHERE with {@code params}; {@code relevance} (higher = better) goes in ORDER BY ... DESC and also
     * needs {@code rankParams}. They are separate because a count query has no ORDER BY, and binding an unused name fails.
     */
    public record Match(String condition, String relevance, Map<String, Object> params, Map<String, Object> rankParams) {
    }

    private TextSearch() {
    }

    /**
     * @param text      what the user typed
     * @param keyColumn the ID-like column (SKU or order number): matches anywhere, and an exact match ranks first
     * @param nameColumn the main words column (product name, customer name): matches anywhere, by word start, and with typos
     * @param otherColumns further columns that match anywhere but rank lower (e.g. category)
     * @return null if there is nothing to search for
     */
    public static Match of(String text, String keyColumn, String nameColumn, List<String> otherColumns) {
        List<String> words = words(text);
        if (words.isEmpty()) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> rankParams = new LinkedHashMap<>();
        List<String> conditions = new ArrayList<>();
        List<String> scores = new ArrayList<>();
        String name = "coalesce(" + nameColumn + ", '')";

        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            String contains = "ts_c" + i;
            String wordStart = "ts_w" + i;
            params.put(contains, "%" + escapeLike(word) + "%");
            rankParams.put(wordStart, "% " + escapeLike(word) + "%");

            List<String> anywhere = new ArrayList<>(List.of(keyColumn + " ILIKE :" + contains, nameColumn + " ILIKE :" + contains));
            List<String> secondary = new ArrayList<>(List.of(keyColumn + " ILIKE :" + contains));
            for (String other : otherColumns) {
                anywhere.add(other + " ILIKE :" + contains);
                secondary.add(other + " ILIKE :" + contains);
            }
            if (word.length() >= MIN_LENGTH_FOR_TYPOS && word.chars().allMatch(Character::isLetter)) {
                String typed = "ts_t" + i;
                params.put(typed, word);
                anywhere.add("word_similarity(:" + typed + ", " + nameColumn + ") >= " + TYPO_SIMILARITY);
            }
            conditions.add("(" + String.join(" OR ", anywhere) + ")");

            // a word that starts a word in the name beats one buried inside a word, which beats a SKU/category hit, which beats a typo match
            scores.add("(CASE WHEN (' ' || " + name + ") ILIKE :" + wordStart + " THEN 10"
                    + " WHEN " + name + " ILIKE :" + contains + " THEN 5"
                    + " WHEN " + String.join(" OR ", secondary) + " THEN 3 ELSE 1 END)");
        }

        String whole = String.join(" ", words);
        rankParams.put("ts_whole", whole);
        rankParams.put("ts_whole_start", escapeLike(whole) + "%");
        rankParams.put("ts_whole_any", "%" + escapeLike(whole) + "%");
        String relevance = "(CASE WHEN lower(" + keyColumn + ") = :ts_whole THEN 100 ELSE 0 END"
                + " + CASE WHEN " + name + " ILIKE :ts_whole_start THEN 20 WHEN " + name + " ILIKE :ts_whole_any THEN 8 ELSE 0 END"
                + " + " + String.join(" + ", scores) + ")";
        return new Match(String.join(" AND ", conditions), relevance, params, rankParams);
    }

    /** Lower-case words, split on spaces and commas, at most {@value #MAX_WORDS}. */
    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        for (String word : text.strip().toLowerCase(Locale.ROOT).split("[\\s,]+")) {
            if (!word.isEmpty() && words.size() < MAX_WORDS) {
                words.add(word);
            }
        }
        return words;
    }

    /** So that a search for "50%" means the characters 50% and not "50 followed by anything". */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
