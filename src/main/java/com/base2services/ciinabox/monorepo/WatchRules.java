package com.base2services.ciinabox.monorepo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Which paths rebuild a project: everything under its own directory, the {@code watch} globs in
 * its {@code monorepo.yaml}, and everything the projects it {@code dependsOn} watch, transitively.
 * All paths are from the repository root.
 */
final class WatchRules {
    static final String FILE = "monorepo.yaml";

    /** Reads a file at the revision being decided; null when it isn't there. */
    interface FileReader {
        String read(String path) throws Exception;
    }

    private WatchRules() {}

    static Set<String> patterns(String dir, FileReader reader) throws Exception {
        Set<String> patterns = new LinkedHashSet<>();
        collect(normalise(dir), reader, patterns, new LinkedHashSet<>());
        return patterns;
    }

    private static void collect(String dir, FileReader reader, Set<String> patterns, Set<String> seen)
            throws Exception {
        if (!seen.add(dir)) {
            return; // a dependency cycle, or a shared dependency already covered
        }
        patterns.add(dir.isEmpty() ? "**" : dir + "/**");
        String text = reader.read(dir.isEmpty() ? FILE : dir + "/" + FILE);
        if (text == null || text.isBlank()) {
            return;
        }
        Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        if (!(doc instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(dir + "/" + FILE + ": expected a mapping");
        }
        for (String glob : strings(map.get("watch"), dir, "watch")) {
            patterns.add(normalise(glob));
        }
        for (String dependency : strings(map.get("dependsOn"), dir, "dependsOn")) {
            collect(normalise(dependency), reader, patterns, seen);
        }
    }

    private static List<String> strings(Object value, String dir, String key) {
        List<String> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(dir + "/" + FILE + ": " + key + " must be a list");
        }
        for (Object item : list) {
            result.add(String.valueOf(item));
        }
        return result;
    }

    private static String normalise(String path) {
        String p = path.trim();
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return ".".equals(p) ? "" : p;
    }

    /** The first changed path that a pattern matches, or null. */
    static String firstMatch(Collection<String> patterns, Collection<String> changed) {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns) {
            compiled.add(Pattern.compile(regex(pattern)));
        }
        for (String path : changed) {
            for (Pattern pattern : compiled) {
                if (pattern.matcher(path).matches()) {
                    return path;
                }
            }
        }
        return null;
    }

    /** {@code **} crosses directories, {@code *} and {@code ?} don't. */
    static String regex(String glob) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;
                        re.append("(?:.*/)?");
                    } else {
                        re.append(".*");
                    }
                } else {
                    re.append("[^/]*");
                }
            } else if (c == '?') {
                re.append("[^/]");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return re.toString();
    }
}
