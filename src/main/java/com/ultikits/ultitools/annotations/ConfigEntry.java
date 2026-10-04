package com.ultikits.ultitools.annotations;

import com.ultikits.ultitools.interfaces.impl.pasers.DefaultConfigParser;
import com.ultikits.ultitools.interfaces.impl.pasers.ConfigParser;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Config entry annotation.
 *
 * @see <a href="https://dev.ultikits.com/en/guide/essentials/config-file.html">Configuration</a>
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigEntry {
    /**
     * Declares a nested entry path split at every dot. Dots inside keys of a bound map are
     * whole map keys instead; they are not interpreted as annotation path separators.
     * @return the nested config entry path
     */
    String path() default "";

    /**
     * A single trimmed {@code {key}} token resolves through the module catalogue on every
     * load and write, so the entry's comment follows the server's language. Only comment lines
     * the framework can identify as its own are rewritten: the entry's comment, as a whole or as
     * its trailing run of lines, equal byte for byte (at the entry's column, {@code "# "} and the
     * text) to the framework's rendering of the token in a catalogue
     * the module's jar ships, of the text the module resolves now, or of the bare token. Any other
     * comment line above the entry - an operator's note, a framework comment the operator edited -
     * is kept byte for byte, permanently. Literal comments are only supplied for new entries and
     * existing operator comments are retained.
     * Comments on individual list items are kept only while the list keeps its length
     * - the same as Bukkit, which keeps none.
     * @return the literal comment or single catalogue-key token
     */
    String comment() default "";

    /**
     * Comment texts earlier versions of the module shipped for this entry - for example the literal
     * comment it had before {@link #comment()} became a {@code {key}} token whose catalogue wording
     * then changed. On a token entry, a comment in a server's file that equals one of these texts in
     * the exact form the framework writes (the entry's column, {@code "# "} and the text) counts as
     * written by the framework: it is replaced by the current catalogue text and follows the server's
     * language from then on. A comment that differs in any character stays the operator's and is kept
     * byte for byte. Ignored on an entry whose comment is literal, which is never rewritten.
     *
     * @return the texts earlier versions shipped, one element per comment (a text may span lines)
     * @since 6.3.0
     */
    String[] previousComments() default {};

    /**
     * The default selects the declared-type converter registry. An explicit non-default
     * parser selects the frozen legacy adapter, including its old dotted-key behavior.
     * @return the legacy parser override, or the registry-selecting default
     * @see DefaultConfigParser
     * @deprecated Use ConfigConverter with ConfigConverterFor; removed in the next version.
     * @removeIn 6.4.0
     */
    // Retains the legacy parser attribute until the announced removal version.
    @SuppressWarnings("removal")
    @Deprecated(since = "6.3.0", forRemoval = true)
    Class<? extends ConfigParser> parser() default DefaultConfigParser.class;
}
