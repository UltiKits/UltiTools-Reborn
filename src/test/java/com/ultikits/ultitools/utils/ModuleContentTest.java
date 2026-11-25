package com.ultikits.ultitools.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleContentTest {

    @Test
    void sortsKnownKindsByConsequenceThenFutureKindsAlphabetically() {
        var content = new ModuleContent(List.of(
                new ModuleContentKind("tag", 1),
                new ModuleContentKind("unknown", null),
                new ModuleContentKind("enchantment", 2),
                new ModuleContentKind("zz_future", 3),
                new ModuleContentKind("datapack", 1),
                new ModuleContentKind("aa_future", 4)));

        assertThat(content.kinds()).extracting(ModuleContentKind::kind)
                .containsExactly("enchantment", "datapack", "tag", "unknown", "aa_future", "zz_future");
        assertThat(content.find("enchantment").count()).isEqualTo(2);
        assertThat(content.find("zz_future").count()).isEqualTo(3);
        assertThat(content.isEmpty()).isFalse();
    }

    @Test
    void rejectsDuplicateKindsRegardlessOfCount() {
        var duplicates = List.of(new ModuleContentKind("tag", 1), new ModuleContentKind("tag", 2));

        assertThatThrownBy(() -> new ModuleContent(duplicates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tag");
    }

    @Test
    void rejectsMoreThanSixteenKinds() {
        var kinds = IntStream.range(0, 17)
                .mapToObj(index -> new ModuleContentKind("kind_" + index, 1))
                .toList();

        assertThat(ModuleContent.MAX_KINDS).isEqualTo(16);
        assertThatThrownBy(() -> new ModuleContent(kinds))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsExactlySixteenKinds() {
        var kinds = IntStream.range(0, 16)
                .mapToObj(index -> new ModuleContentKind("kind_" + index, null))
                .toList();

        assertThat(new ModuleContent(kinds).kinds()).hasSize(16);
    }

    @Test
    void noneHasNoKinds() {
        assertThat(ModuleContent.NONE.isEmpty()).isTrue();
        assertThat(ModuleContent.NONE.kinds()).isEmpty();
    }

    @Test
    void unknownHasExactlyOneUnknownKindWithoutACount() {
        assertThat(ModuleContent.unknown().kinds())
                .containsExactly(new ModuleContentKind("unknown", null));
        assertThat(ModuleContent.unknown().isEmpty()).isFalse();
    }

    @Test
    void findsAnExistingKindAndReturnsNullForAMissingKind() {
        var tag = new ModuleContentKind("tag", 1);
        var content = new ModuleContent(List.of(tag));

        assertThat(content.find("tag")).isEqualTo(tag);
        assertThat(content.find("x")).isNull();
    }

    @Test
    void kindsCannotBeChanged() {
        var content = new ModuleContent(List.of(new ModuleContentKind("tag", 1)));

        assertThatThrownBy(() -> content.kinds().add(new ModuleContentKind("datapack", 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void takesADefensiveCopyWithoutSortingTheInput() {
        var input = new ArrayList<>(List.of(
                new ModuleContentKind("tag", 1), new ModuleContentKind("enchantment", 2)));
        var content = new ModuleContent(input);

        assertThat(input).extracting(ModuleContentKind::kind).containsExactly("tag", "enchantment");
        input.clear();
        assertThat(content.kinds()).extracting(ModuleContentKind::kind)
                .containsExactly("enchantment", "tag");
    }

    @Test
    void treatsANullListAsEmpty() {
        var content = new ModuleContent(null);

        assertThat(content.isEmpty()).isTrue();
        assertThat(content).isEqualTo(ModuleContent.NONE);
    }

    @Test
    void rejectsNullElements() {
        var kinds = Arrays.asList(new ModuleContentKind("tag", 1), null);

        assertThatThrownBy(() -> new ModuleContent(kinds))
                .isInstanceOf(NullPointerException.class);
    }
}
