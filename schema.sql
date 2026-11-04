-- -----------------------------------------------------------------
-- Schema additions for the upgraded SMS (safe to run more than once)
-- -----------------------------------------------------------------

-- Web login uses a plain "password" column in Supabase
alter table users add column if not exists password text;

create table if not exists subjects (
  name        text primary key,
  credits     numeric(4,2) not null default 1.0 check (credits > 0 and credits <= 20),
  description text
);

create table if not exists grade_components (
  id             bigserial primary key,
  student_id     text not null references users(id) on delete cascade,
  subject        text not null,
  component_name text not null,
  score          numeric(5,2) not null check (score >= 0 and score <= 100),
  weight         numeric(5,2) not null default 1.0 check (weight > 0),
  graded_by      text,
  updated_at     bigint not null default (extract(epoch from now())*1000)::bigint,
  comment        text,
  unique (student_id, subject, component_name)
);

create index if not exists idx_gc_student on grade_components(student_id);
create index if not exists idx_gc_subject on grade_components(subject);

alter table audit_log add column if not exists actor text;
alter table audit_log add column if not exists created_at timestamptz default now();

-- -----------------------------------------------------------------
-- One-time migration (only if the old "grades" table exists)
-- -----------------------------------------------------------------
do $$
begin
  if to_regclass('public.grades') is not null then
    insert into grade_components (student_id, subject, component_name, score, weight)
    select g.student_id, g.subject, 'Final', g.score, 1.0
    from grades g
    where not exists (
      select 1 from grade_components gc
      where gc.student_id = g.student_id and gc.subject = g.subject
    );
    execute 'alter table grades enable row level security';
    execute 'drop policy if exists "demo_all" on grades';
    execute 'create policy "demo_all" on grades for all using (true) with check (true)';
  end if;
end $$;

-- -----------------------------------------------------------------
-- Row Level Security  (classroom demo only!)
-- Anyone with the anon key can read/write everything, including
-- plain-text passwords. For production use Supabase Auth + real policies.
-- -----------------------------------------------------------------
alter table users             enable row level security;
alter table subjects          enable row level security;
alter table grade_components  enable row level security;
alter table audit_log         enable row level security;

drop policy if exists "demo_all" on users;
drop policy if exists "demo_all" on subjects;
drop policy if exists "demo_all" on grade_components;
drop policy if exists "demo_all" on audit_log;

create policy "demo_all" on users            for all using (true) with check (true);
create policy "demo_all" on subjects         for all using (true) with check (true);
create policy "demo_all" on grade_components for all using (true) with check (true);
create policy "demo_all" on audit_log        for all using (true) with check (true);

-- -----------------------------------------------------------------
-- Test admin account (change the password!)  ->  login A001 / admin123
-- -----------------------------------------------------------------
insert into users (id, name, role, password)
values ('A001', 'Administrator', 'ADMIN', 'admin123')
on conflict (id) do update set password = excluded.password;