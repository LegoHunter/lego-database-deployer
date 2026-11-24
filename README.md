# lego-database-deployer

Liquibase deployer for the LCMS MySQL schemas: the environment schemas (`lego_sandbox`, `lego_dev` and
`lego_prod`) and the shared `marketplace_pricing` schema. It is the authoritative history of schema
changes. `lego-data-model` (Workbench) and the `lego-data` H2 test
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
- `2.1.0-reference-data` loads the lookup tables every environment needs.
- `2.3.0-pricing-decision-guard` adds the Reprice guard outcome to `pricing_decision`
  (`guard_status_code`, `guard_reason_code`, both nullable; HOMELAB-65).

## The shared pricing changelog

`db/marketplace-pricing/` is a second, separate changelog for the `marketplace_pricing` schema
(HOMELAB-31). It holds the market data every environment reads: the lots each marketplace is offering for
an item, crawled once for all environments.

- It follows the same rules: a new file per change in `db/marketplace-pricing/files/`, objects qualified
  with `${marketplace-pricing-schema}`, never edit a changeset that has run.
- `1.0.0-baseline` creates eight tables: `marketplace`, `canonical_item`, `marketplace_item`,
  `want_list_item`, `crawl_target`, `crawl_attempt`, `market_snapshot` and `market_listing`. It halts on a
  schema that already has `market_snapshot`.
- `1.1.0-reference-data` loads the `marketplace` rows (BrickLink crawled, eBay disabled).
- Environment schemas reference a snapshot by `market_snapshot_id` with no foreign key, because it is in
  another schema (`2.2.0-market-snapshot-reference`).

## Running it

Run `LegoDatabaseDeployerApplication` (IntelliJ or `mvn spring-boot:run`) with:

- **Profile:** `sandbox` (schema `lego_sandbox`), `dev` (schema `lego_dev`) or `prod` (schema
  `lego_prod`, see [PROD](#prod)) for the environment changelog; `marketplace-pricing` (schema `marketplace_pricing`) or `marketplace-pricing-scratch`
  (schema `marketplace_pricing_scratch`, for trial builds) for the shared pricing changelog. The database
  connection comes from `/dev/config/database-configuration.yml` (`database-key-name`). Every profile
  connects with a deployer account, never with the account the services use (HOMELAB-126):

  | Profile | Key | Account |
  |---|---|---|
  | `sandbox` | `rasppi5-01-lego-sandbox-deployer` | `lcms_sandbox_deployer` |
  | `dev` | `rasppi5-01-lego-dev-deployer` | `lcms_dev_deployer` |
  | `prod` | `rasppi5-01-lego-prod-deployer` | `lcms_prod_deployer` |
  | `marketplace-pricing` | `rasppi5-01-marketplace-pricing` | `lcms_pricing_deployer` |
  | `marketplace-pricing-scratch` | `rasppi5-01-marketplace-pricing-scratch` | `lcms_pricing_deployer` |

  Each key's JDBC URL has that profile's schema as its default schema. Each account has ALL on its own
  schema (the pricing one on both pricing schemas) and nothing anywhere else. The accounts are created by
  `lego-server-configuration/mysql/accounts/setup-mysql-accounts.sh` (PROD: `setup-lego-prod.sh`).
- **`deployer.changelog`:** `ENVIRONMENT` (default) or `MARKETPLACE_PRICING`. The pricing profiles set it.
- **`deployer.mode`:**
  - `UPDATE` (default): apply pending changesets.
  - `STATUS`: validate checksums and log pending changesets; applies nothing. Run it before every
    `UPDATE`.
  - `CHANGELOG_SYNC`: record pending changesets as applied **without running them**. Use it only to bring
    an existing, already-correct schema under Liquibase.

The run is refused unless the connection's schema (`SELECT DATABASE()`) equals the profile's schema
parameter. The environment changelog refuses any `marketplace_pricing*` schema and the pricing changelog
refuses every other schema. Every run is refused for `lego_prod_legacy`.

## PROD

`lego_prod` is the next-generation PROD database (HOMELAB-56). It is not `lego_prod_legacy`, which this
deployer refuses to touch.

- The `prod` profile connects with its own key, `rasppi5-01-lego-prod-deployer`: the account
  `lcms_prod_deployer`, which has ALL on `lego_prod` and nothing anywhere else. The key exists only in the
  owner's `/dev/config/database-configuration.yml`.
- The PROD services use a different account (`lcms_prod_app`, key `rasppi5-01-lego-prod`) that has no DDL.
  The deployer cannot run with it, by design.
- The empty schema and both accounts are created first by
  `00-workspace/ops/lego-prod/setup-lego-prod.sh` in the workspace repository; its README gives the order.
- A PROD run changes the live PROD schema. Run `STATUS` first and read what is pending. Apply a changeset
  to `sandbox` and `dev` before `prod`.

## Making a schema change

1. Add a new changeset file here.
2. Make the same change in `lego-data-model` (Workbench) and in the `lego-data` H2 schema and mappings.
3. Run `STATUS`, then `UPDATE`, against `sandbox`. Then do the same for `dev`, and last for `prod`.

## Current schema notes

- `marketplace_listing.unit_price` is nullable. A local non-fixed `DRAFT` may enter Pricing Plane
  onboarding without a price; the Pricing Plane applies a positive initial price before a BrickLink
  `LISTING_CREATE`.
- `bricklink_marketplace_listing.stock_room_id` is nullable. PROD lots are public only when they are not in
  a stockroom. SANDBOX and DEV are kept in their own stockrooms by application checks, not by a `NOT NULL`
  constraint.
- `item_inventory_seed_source` records where lower-environment seeded items came from (HOMELAB-40). PROD
  never holds rows.
