package de.lovinoes.networkutilitiescommon.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.representer.Representer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Brings an existing config.yml up to date with the one shipped inside the jar, so a plugin
 * update never leaves an operator hunting for options that were added, or puzzling over ones
 * that no longer do anything.
 *
 * What it does on load:
 * <ul>
 *   <li>adds any key the shipped default has and the file does not, together with the comments
 *       documenting it, positioned where the default puts it</li>
 *   <li>removes any key the file has and the shipped default does not</li>
 *   <li>leaves every value that exists in both exactly as the operator wrote it</li>
 * </ul>
 *
 * The operator's file is the tree that gets edited, not the default, so their own values,
 * formatting, quoting style and any comments they added themselves all survive. Only genuinely
 * new keys bring anything across from the default.
 *
 * The file is rewritten only when something actually changed. An unchanged config is never
 * touched, so a normal restart cannot reformat or reorder anything. When it is rewritten, the
 * previous file is kept alongside it as config.yml.bak first, so nothing is ever only in the
 * version being replaced.
 */
public final class ConfigMigrator {

    private static final System.Logger LOGGER = System.getLogger(ConfigMigrator.class.getName());

    private ConfigMigrator() {
    }

    /**
     * Kept beside each config, holding the defaults that shipped with the version that last
     * wrote it. Without a record of what the defaults used to be, an operator's value and an
     * old default look identical, and improving a default message could never reach anyone who
     * had already started the plugin once.
     */
    private static final String BASELINE_SUFFIX = ".defaults";

    /** What a migration did, for logging. Empty when the file was already up to date. */
    public record Result(List<String> added, List<String> removed, List<String> updated) {

        public boolean changed() {
            return !added.isEmpty() || !removed.isEmpty() || !updated.isEmpty();
        }
    }

    private static final Result UNCHANGED = new Result(List.of(), List.of(), List.of());

    /**
     * Reconciles {@code target} against the shipped defaults and rewrites it if needed.
     *
     * Never throws for a malformed or unusual file: migration is a convenience, and refusing to
     * start a plugin because it could not tidy a config would be far worse than leaving the
     * config alone. Anything it cannot safely handle is reported as unchanged.
     */
    public static Result migrate(Path target, InputStream defaults) throws IOException {
        // Read the defaults once and keep the bytes: they are parsed now and, if this succeeds,
        // written back out as the baseline for next time.
        byte[] defaultBytes = defaults.readAllBytes();
        Path baseline = target.resolveSibling(target.getFileName() + BASELINE_SUFFIX);

        Node defaultRoot;
        Node userRoot;
        Node baselineRoot = null;
        try (Reader defaultReader = new InputStreamReader(
                     new ByteArrayInputStream(defaultBytes), StandardCharsets.UTF_8);
             Reader userReader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            Yaml parser = commentAwareYaml();
            defaultRoot = parser.compose(defaultReader);
            userRoot = parser.compose(userReader);
            if (Files.exists(baseline)) {
                try (Reader baselineReader = Files.newBufferedReader(baseline, StandardCharsets.UTF_8)) {
                    baselineRoot = parser.compose(baselineReader);
                }
            }
        } catch (RuntimeException e) {
            // A file the operator has broken is their business; report it by leaving it alone
            // rather than rewriting something we did not fully understand.
            return UNCHANGED;
        }

        // An empty file on either side gives null, and a non-mapping root is not a config we
        // know how to reconcile. Both mean there is nothing safe to do.
        if (!(defaultRoot instanceof MappingNode defaultMapping)
                || !(userRoot instanceof MappingNode userMapping)) {
            return UNCHANGED;
        }

        // Refuse to reconcile two files that plainly are not the same config. Migration removes
        // whatever the default does not have, so being handed the wrong default would rewrite
        // an operator's file into something unrelated. Sharing not one single top-level key is
        // not an upgrade, it is a mix-up, and the only safe response is to touch nothing.
        if (!sharesAnyTopLevelKey(defaultMapping, userMapping)) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Refusing to update {0}: it has nothing in common with the defaults shipped for it. "
                            + "Leaving it exactly as it is.", target.getFileName());
            return UNCHANGED;
        }

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        merge(defaultMapping, userMapping,
                baselineRoot instanceof MappingNode baselineMapping ? baselineMapping : null,
                "", added, removed, updated);

        if (added.isEmpty() && removed.isEmpty() && updated.isEmpty()) {
            // Still record the defaults, so the first run after this was added establishes the
            // baseline that later upgrades compare against.
            writeBaseline(baseline, defaultBytes);
            return UNCHANGED;
        }

        String rewritten = serialize(userMapping);

        // Write the backup before the file it backs up, so an interrupted migration can only
        // ever leave the operator with more copies of their config, never fewer.
        Files.copy(target, target.resolveSibling(target.getFileName() + ".bak"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(target, rewritten, StandardCharsets.UTF_8);
        writeBaseline(baseline, defaultBytes);

        return new Result(List.copyOf(added), List.copyOf(removed), List.copyOf(updated));
    }

    /**
     * Rebuilds the user mapping's entries in the default's order: entries present in both keep
     * the user's own tuple, entries only in the default are taken across whole (which carries
     * their comments), and entries only in the user's file are dropped.
     */
    private static void merge(MappingNode defaults, MappingNode user, MappingNode baseline, String prefix,
                               List<String> added, List<String> removed, List<String> updated) {
        Map<String, NodeTuple> userByKey = new LinkedHashMap<>();
        for (NodeTuple tuple : user.getValue()) {
            String key = keyOf(tuple);
            if (key != null) {
                userByKey.put(key, tuple);
            }
        }

        Map<String, NodeTuple> baselineByKey = new LinkedHashMap<>();
        if (baseline != null) {
            for (NodeTuple tuple : baseline.getValue()) {
                String key = keyOf(tuple);
                if (key != null) {
                    baselineByKey.put(key, tuple);
                }
            }
        }

        Map<String, NodeTuple> defaultByKey = new LinkedHashMap<>();
        for (NodeTuple tuple : defaults.getValue()) {
            String key = keyOf(tuple);
            if (key != null) {
                defaultByKey.put(key, tuple);
            }
        }

        List<NodeTuple> merged = new ArrayList<>();

        // The operator's own ordering comes first and is kept. Rebuilding in the default's
        // order instead would quietly rearrange a file every time anything was added, undoing
        // whatever grouping they had arranged for themselves.
        for (NodeTuple userTuple : user.getValue()) {
            String key = keyOf(userTuple);
            if (key == null) {
                continue;
            }
            NodeTuple defaultTuple = defaultByKey.get(key);
            if (defaultTuple == null) {
                removed.add(path(prefix, key));
                continue;
            }

            NodeTuple baselineTuple = baselineByKey.get(key);

            if (defaultTuple.getValueNode() instanceof MappingNode defaultChild) {
                if (userTuple.getValueNode() instanceof MappingNode userChild) {
                    // Recurse so a new key nested inside a section the operator already has is
                    // still picked up. userChild is edited in place, so the tuple stays valid.
                    MappingNode baselineChild =
                            baselineTuple != null && baselineTuple.getValueNode() instanceof MappingNode child
                                    ? child
                                    : null;
                    merge(defaultChild, userChild, baselineChild, path(prefix, key),
                            added, removed, updated);
                } else {
                    // The option used to be a list or a single value and is now a section. The
                    // old value cannot be read as the new shape, so the only thing that can be
                    // done with it is to replace it, which is reported like any other change.
                    // The previous file is still kept as .bak.
                    merged.add(defaultTuple);
                    removed.add(path(prefix, key));
                    added.add(path(prefix, key));
                    continue;
                }
            } else if (baselineTuple != null
                    && sameValue(userTuple.getValueNode(), baselineTuple.getValueNode())
                    && !sameValue(defaultTuple.getValueNode(), baselineTuple.getValueNode())) {
                // The operator still has exactly what the last version shipped, and this
                // version ships something different. They never expressed an opinion about
                // this value, so an improved default is allowed to reach them. Change it
                // yourself and this stops touching it, because it no longer matches.
                merged.add(defaultTuple);
                updated.add(path(prefix, key));
                continue;
            }
            merged.add(userTuple);
        }

        // Then whatever this version added, in the order the defaults list it.
        for (Map.Entry<String, NodeTuple> entry : defaultByKey.entrySet()) {
            if (!userByKey.containsKey(entry.getKey())) {
                merged.add(entry.getValue());
                added.add(path(prefix, entry.getKey()));
            }
        }

        user.setValue(merged);
    }

    /**
     * An empty file on either side counts as shared, since there is nothing to disagree about
     * and a brand new config legitimately starts with no keys at all.
     */
    private static boolean sharesAnyTopLevelKey(MappingNode defaults, MappingNode user) {
        Set<String> defaultKeys = new LinkedHashSet<>();
        for (NodeTuple tuple : defaults.getValue()) {
            String key = keyOf(tuple);
            if (key != null) {
                defaultKeys.add(key);
            }
        }
        if (defaultKeys.isEmpty() || user.getValue().isEmpty()) {
            return true;
        }
        for (NodeTuple tuple : user.getValue()) {
            if (defaultKeys.contains(keyOf(tuple))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether two nodes carry the same value, ignoring comments, quoting style and formatting,
     * which is what "the operator has not changed this" has to mean. Two scalars written as
     * "x" and 'x' are the same value even though the files differ.
     */
    private static boolean sameValue(Node left, Node right) {
        if (left instanceof ScalarNode leftScalar && right instanceof ScalarNode rightScalar) {
            return leftScalar.getValue().equals(rightScalar.getValue());
        }
        if (left instanceof SequenceNode leftSequence && right instanceof SequenceNode rightSequence) {
            List<Node> leftItems = leftSequence.getValue();
            List<Node> rightItems = rightSequence.getValue();
            if (leftItems.size() != rightItems.size()) {
                return false;
            }
            for (int i = 0; i < leftItems.size(); i++) {
                if (!sameValue(leftItems.get(i), rightItems.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof MappingNode leftMapping && right instanceof MappingNode rightMapping) {
            if (leftMapping.getValue().size() != rightMapping.getValue().size()) {
                return false;
            }
            Map<String, Node> rightByKey = new LinkedHashMap<>();
            for (NodeTuple tuple : rightMapping.getValue()) {
                rightByKey.put(keyOf(tuple), tuple.getValueNode());
            }
            for (NodeTuple tuple : leftMapping.getValue()) {
                Node counterpart = rightByKey.get(keyOf(tuple));
                if (counterpart == null || !sameValue(tuple.getValueNode(), counterpart)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Failing to record the baseline must never fail the migration: at worst the next upgrade
     * behaves the way it did before this existed and leaves the operator's values alone.
     */
    private static void writeBaseline(Path baseline, byte[] defaultBytes) {
        try {
            Files.write(baseline, defaultBytes);
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Could not record the shipped defaults next to " + baseline.getFileName()
                            + "; changed defaults will not reach this config automatically.", e);
        }
    }

    private static String keyOf(NodeTuple tuple) {
        return tuple.getKeyNode() instanceof ScalarNode scalar ? scalar.getValue() : null;
    }

    private static String path(String prefix, String key) {
        return prefix.isEmpty() ? key : prefix + "." + key;
    }

    private static String serialize(MappingNode root) {
        Yaml emitter = commentAwareYaml();
        try (Writer writer = new StringWriter()) {
            emitter.serialize(root, writer);
            return tidy(writer.toString());
        } catch (IOException e) {
            // StringWriter does not do IO, so this cannot happen.
            throw new IllegalStateException(e);
        }
    }

    /**
     * The emitter indents the blank line that separated two sections, leaving a line of spaces.
     * Only whitespace-only lines are emptied, never trailing spaces on a line with content,
     * because those can be significant inside a block scalar.
     */
    private static String tidy(String yaml) {
        String[] lines = yaml.split("\n", -1);
        StringBuilder out = new StringBuilder(yaml.length());
        for (int i = 0; i < lines.length; i++) {
            out.append(lines[i].isBlank() ? "" : lines[i]);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static Yaml commentAwareYaml() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setProcessComments(true);

        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setProcessComments(true);
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setIndent(2);
        // Without this, a nested list is emitted flush with its key instead of indented under
        // it. Both parse the same, but only one looks like the file the operator started with.
        dumperOptions.setIndicatorIndent(2);
        dumperOptions.setIndentWithIndicator(true);
        dumperOptions.setSplitLines(false);

        return new Yaml(new SafeConstructor(loaderOptions), new Representer(dumperOptions),
                dumperOptions, loaderOptions);
    }
}
