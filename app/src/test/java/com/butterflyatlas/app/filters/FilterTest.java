package com.butterflyatlas.app.filters;

import com.butterflyatlas.app.AtlasException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The filter in a query string (4a contract §1): parsing, defaults, limits, messages and printing. */
class FilterTest {

    /** No filter: the whole group, any photo, any place. */
    private static final Filter NONE = new Filter(List.of(), List.of(), "any", List.of(), "all", "all", null);

    private static void refused(String query, String message) {
        assertThatThrownBy(() -> Filter.parse(query)).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.kind()).isEqualTo(AtlasException.Kind.INVALID);
            assertThat(e.code()).isEqualTo("invalid");
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    private static String ids(String prefix, int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> prefix + i).collect(Collectors.joining(","));
    }

    /** The cases the UI's filter.test.js reads too, so both sides read and print a query string alike. */
    private static final Path PARITY = Path.of("../fixtures/filter-parity.json");

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(item -> out.add(item.asText()));
        return out;
    }

    @TestFactory
    List<DynamicTest> readsAndPrintsAsTheUiDoes() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : new ObjectMapper().readTree(PARITY.toFile()).get("cases")) {
            JsonNode f = c.get("filter");
            Filter expected = new Filter(strings(f.get("taxa")), strings(f.get("tags")), f.get("tagMode").asText(),
                    strings(f.get("places")), f.get("status").asText(), f.get("source").asText(),
                    f.get("stages").isNull() ? null : f.get("stages").asText(),
                    f.has("types") ? strings(f.get("types")) : List.of());
            String printed = c.get("printed").asText();
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                assertThat(Filter.parse(c.get("query").asText())).isEqualTo(expected);
                assertThat(expected.query()).isEqualTo(printed);
                assertThat(Filter.parse(printed)).isEqualTo(expected);
            }));
        }
        assertThat(tests).hasSizeGreaterThan(10);
        return tests;
    }

    @Test
    void noQueryIsTheWholeGroupWithEveryDefault() {
        for (String query : new String[] {null, "", "&", "view=photos&after=x&limit=3"}) {
            Filter filter = Filter.parse(query);
            assertThat(filter).isEqualTo(NONE);
            assertThat(filter.query()).isEmpty();
        }
        assertThat(NONE.byStages()).isFalse();
    }

    @Test
    void everyKeyIsReadAndPrintedInTheContractOrder() {
        Filter filter = Filter.parse("source=manual&status=not&places=P20,unknown&tagMode=all"
                + "&tags=egg,larva&taxa=A12,A230");

        assertThat(filter).isEqualTo(new Filter(List.of("A12", "A230"), List.of("egg", "larva"), "all",
                List.of("P20", "unknown"), "not", "manual", null));
        assertThat(filter.byStages()).isFalse();
        assertThat(filter.query()).isEqualTo("taxa=A12,A230&tags=egg,larva&tagMode=all&places=P20,unknown&status=not"
                + "&source=manual");
        assertThat(Filter.parse(filter.query())).isEqualTo(filter);

        Filter stages = Filter.parse("stages=missing&tagMode=all&source=manual&places=P20&taxa=A12");
        assertThat(stages).isEqualTo(new Filter(List.of("A12"), List.of(), "all", List.of("P20"), "all", "manual",
                "missing"));
        assertThat(stages.byStages()).isTrue();
        assertThat(stages.query()).isEqualTo("taxa=A12&tagMode=all&places=P20&source=manual&stages=missing");
    }

    @Test
    void lifeStagesAreRefusedWithTagsOrAPhotoStatus() {
        String message = "Life stages can't be combined with Tags or Photo status.";
        refused("tags=egg&stages=missing", message);
        refused("stages=missing&status=photographed", message);
        refused("stages=missing&status=not", message);
        refused("stages=missing&tags=male&tagMode=all&status=not", message);
        refused("stages=complete&tags=egg", message);
        refused("stages=complete&status=photographed", message);
        assertThat(Filter.parse("stages=missing&status=all&tags=").stages()).isEqualTo("missing");
    }

    @Test
    void completeLifeStagesIsReadAndPrinted() {
        Filter filter = Filter.parse("types=raw&stages=complete&places=P4&taxa=A1");

        assertThat(filter.stages()).isEqualTo("complete");
        assertThat(filter.byStages()).isTrue();
        assertThat(filter.query()).isEqualTo("taxa=A1&places=P4&stages=complete&types=raw");
    }

    @Test
    void fileTypesAreCheckedKeepTheirOrderAndComeLast() {
        Filter filter = Filter.parse("types=raw,JPEG".toLowerCase() + ",raw&source=manual");

        assertThat(filter.types()).containsExactly("raw", "jpeg");
        assertThat(filter.query()).isEqualTo("source=manual&types=raw,jpeg");
        assertThat(Filter.parse("").types()).isEmpty();
        assertThatThrownBy(() -> Filter.parse("types=raw,gif")).isInstanceOfSatisfying(AtlasException.class, e -> {
            assertThat(e.code()).isEqualTo("invalid");
            assertThat(e.getMessage()).isEqualTo("Unknown file type “gif”.");
        });
        assertThatThrownBy(() -> Filter.parse("types=JPEG")).hasMessage("Unknown file type “JPEG”.");
        assertThat(Filter.parse("types=heic,raw").query()).as("Phase 7b").isEqualTo("types=heic,raw");
        assertThatThrownBy(() -> Filter.parse("types=heif")).hasMessage("Unknown file type “heif”.");
    }

    @Test
    void defaultsAreLeftOutWhenPrinted() {
        assertThat(Filter.parse("tagMode=any&status=all&source=all&taxa=A1").query()).isEqualTo("taxa=A1");
        assertThat(Filter.parse("status=photographed&source=official").query()).isEqualTo("status=photographed&source=official");
        assertThat(Filter.parse("tagMode=all").query()).isEqualTo("tagMode=all");
    }

    @Test
    void idsAreTrimmedDeDuplicatedAndKeepTheirOrder() {
        Filter filter = Filter.parse("taxa=A5,%20A1,,A5,A1&tags=larva,egg,larva&places=unknown,P4,unknown");

        assertThat(filter.taxa()).containsExactly("A5", "A1");
        assertThat(filter.tags()).containsExactly("larva", "egg");
        assertThat(filter.places()).containsExactly("unknown", "P4");
        assertThat(filter.query()).isEqualTo("taxa=A5,A1&tags=larva,egg&places=unknown,P4");
    }

    @Test
    void valuesAreDecodedAndPrintedSafely() {
        Filter filter = Filter.parse("taxa=G%3Abutterflies,U7&tags=mud%2Dpuddling,a%26b");

        assertThat(filter.taxa()).containsExactly("G:butterflies", "U7");
        assertThat(filter.tags()).containsExactly("mud-puddling", "a&b");
        assertThat(filter.query()).isEqualTo("taxa=G:butterflies,U7&tags=mud-puddling,a%26b");
        assertThat(Filter.parse(filter.query())).isEqualTo(filter);
    }

    @Test
    void listsAreSplitOnCommasBeforeDecodingSoAnEncodedCommaStaysInItsId() {
        Filter filter = Filter.parse("tags=a%2Cb,c&taxa=A1%2C%20A2&places=P4%2C,unknown");

        assertThat(filter.tags()).containsExactly("a,b", "c");
        assertThat(filter.taxa()).containsExactly("A1, A2");
        assertThat(filter.places()).containsExactly("P4,", "unknown");
        assertThat(filter.query()).isEqualTo("taxa=A1%2C%20A2&tags=a%2Cb,c&places=P4%2C,unknown");
        assertThat(Filter.parse(filter.query())).isEqualTo(filter);
    }

    @Test
    void aNonAsciiTagKeyRoundTrips() {
        Filter filter = Filter.parse("tags=%C3%A9clos%C3%A9,%20larva%20");

        assertThat(filter.tags()).containsExactly("\u00e9clos\u00e9", "larva");
        assertThat(filter.query()).isEqualTo("tags=%C3%A9clos%C3%A9,larva");
        assertThat(Filter.parse(filter.query())).isEqualTo(filter);
    }

    @Test
    void theChoicesOfAFilterCannotBeNull() {
        assertThatThrownBy(() -> new Filter(List.of(), List.of(), null, List.of(), "all", "all", null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("tagMode");
        assertThatThrownBy(() -> new Filter(List.of(), List.of(), "any", List.of(), null, "all", null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("status");
        assertThatThrownBy(() -> new Filter(List.of(), List.of(), "any", List.of(), "all", null, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("source");
    }

    @Test
    void anEmptyValueIsTheDefaultAndTheFirstOfARepeatedKeyCounts() {
        assertThat(Filter.parse("taxa=&tags=&tagMode=&places=&status=&source=&stages=")).isEqualTo(NONE);
        assertThat(Filter.parse("status=not&status=bogus").status()).isEqualTo("not");
    }

    @Test
    void atMost200TaxaAnd200Places() {
        assertThat(Filter.parse("taxa=" + ids("A", 200)).taxa()).hasSize(200);
        assertThat(Filter.parse("taxa=" + ids("A", 200) + ",A1,A2").taxa()).hasSize(200);
        assertThat(Filter.parse("places=" + ids("P", 199) + ",unknown").places()).hasSize(200);
        refused("taxa=" + ids("A", 201), "Choose at most 200 taxa.");
        refused("places=" + ids("P", 200) + ",unknown", "Choose at most 200 places.");
    }

    @Test
    void aValueOutsideItsListIsRefusedInPlainWords() {
        refused("status=some", "Unknown status “some”.");
        refused("tagMode=every", "Unknown tag mode “every”.");
        refused("source=inat", "Unknown source “inat”.");
        refused("stages=all", "Unknown stages “all”.");
        refused("status=ALL", "Unknown status “ALL”.");
    }

    @Test
    void theFilterAsUsedKeepsEverythingButTheIds() {
        Filter filter = Filter.parse("taxa=A1,A404&tags=egg,nope&tagMode=all&places=P4,P404&status=photographed");

        Filter used = filter.withIds(List.of("A1"), List.of("egg"), List.of("P4"));

        assertThat(used).isEqualTo(new Filter(List.of("A1"), List.of("egg"), "all", List.of("P4"), "photographed", "all", null));
        assertThat(used.query()).isEqualTo("taxa=A1&tags=egg&tagMode=all&places=P4&status=photographed");
    }
}
