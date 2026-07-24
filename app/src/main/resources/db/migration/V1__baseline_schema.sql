-- V1 baseline schema for the einvoice-at persistence layer.
--
-- SPEC section 8 speaks of Postgres "schemas" (tenant, invoice, report, audit_event, api_key);
-- these are realized as tables in the single `public` schema, not as separate SQL schemas.
-- See ADR-0005 for the rationale. Flyway owns the schema from V1 on; Hibernate runs with
-- ddl-auto=validate and never mutates it. Identifiers are application-assigned UUIDs (no DB
-- sequences); timestamps are timestamptz. Monetary values use numeric with explicit precision.

create table tenant (
  id uuid primary key,
  external_subject varchar(255) not null unique,  -- Keycloak sub claim
  display_name varchar(255) not null,
  created_at timestamptz not null
);
create table invoice (
  id uuid primary key,
  tenant_id uuid not null references tenant(id),
  invoice_number varchar(255) not null,
  type_code varchar(3) not null,
  issue_date date not null,
  currency char(3) not null,
  payable_amount numeric(17,2) not null,
  seller_name varchar(512) not null,
  buyer_name varchar(512) not null,
  canonical jsonb not null,
  created_at timestamptz not null,
  unique (tenant_id, invoice_number)
);
create table report (
  id uuid primary key,
  tenant_id uuid not null references tenant(id),
  invoice_id uuid references invoice(id),
  source_format varchar(64) not null,
  profile varchar(64) not null,
  valid boolean not null,
  findings jsonb not null,
  created_at timestamptz not null
);
create table api_key (
  id uuid primary key,
  tenant_id uuid not null references tenant(id),
  name varchar(255) not null,
  key_hash char(64) not null unique,   -- SHA-256 hex; plaintext never stored
  prefix varchar(12) not null,
  created_at timestamptz not null,
  revoked_at timestamptz
);
create table audit_event (
  id uuid primary key,
  tenant_id uuid not null references tenant(id),
  action varchar(64) not null,
  payload_sha256 char(64),
  occurred_at timestamptz not null
);
create index on invoice (tenant_id, created_at desc);
create index on report (tenant_id, created_at desc);
create index on audit_event (tenant_id, occurred_at desc);
