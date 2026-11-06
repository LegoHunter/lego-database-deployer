# lego-database-deployer

Liquibase deployer for the LCMS MySQL schemas (`lego_sandbox`, `lego_dev`, and later `lego_prod`). It is
the authoritative history of schema changes. `lego-data-model` (Workbench) and the `lego-data` H2 test
schema follow it.

## Changelog

- `db/changelog/files/2.0.0-baseline.yaml` is the starting schema: the whole Workbench model,
  forward-engineered on 2026-11-05 (52 tables). It replaced changesets 1.0.0–1.2.0, which no longer
  matched the databases (HOMELAB-46).
  - It has no DROP statements.
  - It halts on any schema that already has `item_inventory`.
- Every later change is a **new file** in `db/changelog/files/`, picked up by `includeAll` in file-name
  order. Name it `<version>-<slug>.yaml`, qualify objects with `${environment-schema}`, and give it
  preconditions and a rollback.
- **Never edit a changeset that has already run anywhere.** Liquibase stores each changeset's checksum and
  refuses a changed file. Write a new changeset instead.
- The changelog creates structure only; it loads no reference or lookup data.

## Running it

Run `LegoDatabaseDeployerApplication` (IntelliJ or `mvn spring-boot:run`) with:

- **Profile:** `sandbox` (schema `lego_sandbox`) or `dev` (schema `lego_dev`). The database
  connection comes from `/dev/config/database-configuration.yml` (`database-key-name`).
- **`deployer.mode`:**
  - `UPDATE` (default): apply pending changesets.
  - `STATUS`: validate checksums and log pending changesets; applies nothing. Run it before every
    `UPDATE`.
  - `CHANGELOG_SYNC`: record pending changesets as applied **without running them**. Use it only to bring
    an existing, already-correct schema under Liquibase.

The run is refused unless the connection's schema (`SELECT DATABASE()`) equals the profile's
`environment-schema`. It is always refused for `lego_prod_legacy`.

## Making a schema change

1. Add a new changeset file here.
2. Make the same change in `lego-data-model` (Workbench) and in the `lego-data` H2 schema and mappings.
3. Run `STATUS`, then `UPDATE`, against `sandbox`. Then do the same for `dev` when DEV is in use.

## Current schema notes

- `marketplace_listing.unit_price` is nullable. A local non-fixed `DRAFT` may enter Pricing Plane
  onboarding without a price; the Pricing Plane applies a positive initial price before a BrickLink
  `LISTING_CREATE`.
- `bricklink_marketplace_listing.stock_room_id` is nullable. PROD lots are public only when they are not in
  a stockroom. SANDBOX and DEV are kept in their own stockrooms by application checks, not by a `NOT NULL`
  constraint.
- `item_inventory_seed_source` records where lower-environment seeded items came from (HOMELAB-40). PROD
  never holds rows.
