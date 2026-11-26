package net.lego.database.deployer.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The changelog files apply by version number: 2.10.0 after 2.9.0, where text order puts it after 2.1.0. */
class ChangelogFileOrderTest {

    private final ChangelogFileOrder order = new ChangelogFileOrder();

    @Test
    void aTwoDigitVersionPartFollowsTheSingleDigitOnes() {
        List<String> files = new ArrayList<>(List.of(
                "db/changelog/files/2.10.0-wooden-toys-catalog.yaml",
                "db/changelog/files/2.2.0-market-snapshot-reference.yaml",
                "db/changelog/files/2.9.0-photo-upload-batches.yaml",
                "db/changelog/files/2.1.0-reference-data.yaml",
                "db/changelog/files/2.11.0-later.yaml",
                "db/changelog/files/2.0.0-baseline.yaml",
                "db/changelog/files/3.0.0-next.yaml",
                "db/changelog/files/2.10.1-fix.yaml"));

        files.sort(order);

        assertThat(files).containsExactly(
                "db/changelog/files/2.0.0-baseline.yaml",
                "db/changelog/files/2.1.0-reference-data.yaml",
                "db/changelog/files/2.2.0-market-snapshot-reference.yaml",
                "db/changelog/files/2.9.0-photo-upload-batches.yaml",
                "db/changelog/files/2.10.0-wooden-toys-catalog.yaml",
                "db/changelog/files/2.10.1-fix.yaml",
                "db/changelog/files/2.11.0-later.yaml",
                "db/changelog/files/3.0.0-next.yaml");
    }

    @Test
    void theFilesThatHadAlreadyRunKeepTheOrderTextOrderGaveThem() {
        List<String> ran = List.of(
                "2.0.0-baseline.yaml", "2.1.0-reference-data.yaml", "2.2.0-market-snapshot-reference.yaml",
                "2.3.0-pricing-decision-guard.yaml", "2.4.0-drop-legacy-pricing.yaml", "2.5.0-photo-keywords.yaml",
                "2.6.0-lot-image-sync.yaml", "2.7.0-extended-description-sync.yaml",
                "2.8.0-photo-upload-tokens.yaml", "2.9.0-photo-upload-batches.yaml");
        List<String> byText = new ArrayList<>(ran);
        byText.sort(String::compareTo);
        List<String> byVersion = new ArrayList<>(ran);
        byVersion.sort(order);

        assertThat(byVersion).isEqualTo(byText).isEqualTo(ran);
    }

    @Test
    void equalNamesCompareEqualAndAShorterNameComesFirst() {
        assertThat(order.compare("2.10.0-a.yaml", "2.10.0-a.yaml")).isZero();
        assertThat(order.compare("2.10.0-a", "2.10.0-a.yaml")).isNegative();
        assertThat(order.compare("2.10.0-b.yaml", "2.10.0-a.yaml")).isPositive();
    }
}
