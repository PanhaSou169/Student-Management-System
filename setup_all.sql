-- =====================================================================
-- Student Management System — ONE script that sets up everything.
-- Paste ALL of it into Supabase -> SQL Editor -> Run.  Safe to re-run.
-- =====================================================================

-- ---------- core tables (created only if missing) ----------
create table if not exists public.users (
  id       text primary key,
  name     text not null,
  role     text not null check (role in ('ADMIN','TEACHER','STUDENT')),
  subject  text,
  password text
);
alter table public.users add column if not exists password text;
alter table public.users add column if not exists subject  text;

create table if not exists public.audit_log (
  id         bigserial primary key,
  actor      text,
  action     text,
  details    text,
  created_at timestamptz default now()
);
alter table public.audit_log add column if not exists actor      text;
alter table public.audit_log add column if not exists details    text;
alter table public.audit_log add column if not exists created_at timestamptz default now();

-- ---------- grading ----------
create table if not exists public.subjects (
  name        text primary key,
  credits     numeric(4,2) not null default 1.0 check (credits > 0 and credits <= 20),
  description text
);

create table if not exists public.grade_components (
  id             bigserial primary key,
  student_id     text not null references public.users(id) on delete cascade,
  subject        text not null,
  component_name text not null,
  score          numeric(5,2) not null check (score >= 0 and score <= 100),
  weight         numeric(5,2) not null default 1.0 check (weight > 0),
  graded_by      text,
  updated_at     bigint not null default (extract(epoch from now())*1000)::bigint,
  comment        text,
  unique (student_id, subject, component_name)
);
create index if not exists idx_gc_student on public.grade_components(student_id);
create index if not exists idx_gc_subject on public.grade_components(subject);

-- ---------- school features ----------
create table if not exists public.attendance (
  id         bigserial primary key,
  student_id text not null references public.users(id) on delete cascade,
  subject    text not null,
  date       date not null,
  status     text not null check (status in ('PRESENT','LATE','ABSENT','EXCUSED')),
  marked_by  text,
  unique (student_id, subject, date)
);
create index if not exists idx_att_subject on public.attendance(subject, date);

create table if not exists public.announcements (
  id          bigserial primary key,
  title       text not null,
  body        text not null,
  audience    text not null default 'ALL' check (audience in ('ALL','STUDENT','TEACHER')),
  subject     text,
  author_id   text,
  author_name text,
  created_at  timestamptz not null default now()
);

create table if not exists public.assignments (
  id          bigserial primary key,
  subject     text not null,
  title       text not null,
  description text,
  due_date    date not null,
  max_score   numeric(6,2) not null default 100 check (max_score > 0),
  created_by  text,
  created_at  timestamptz not null default now()
);
create index if not exists idx_asg_subject on public.assignments(subject);

create table if not exists public.submissions (
  id            bigserial primary key,
  assignment_id bigint not null references public.assignments(id) on delete cascade,
  student_id    text   not null references public.users(id) on delete cascade,
  content       text   not null,
  submitted_at  timestamptz not null default now(),
  score         numeric(6,2),
  feedback      text,
  graded_by     text,
  unique (assignment_id, student_id)
);

-- ---------- migrate the old single-score "grades" table (only if it exists) ----------
do $$
begin
  if to_regclass('public.grades') is not null then
    insert into public.grade_components (student_id, subject, component_name, score, weight)
    select g.student_id, g.subject, 'Final', g.score, 1.0
    from public.grades g
    where not exists (
      select 1 from public.grade_components gc
      where gc.student_id = g.student_id and gc.subject = g.subject
    );
    execute 'alter table public.grades enable row level security';
    execute 'drop policy if exists "demo_all" on public.grades';
    execute 'create policy "demo_all" on public.grades for all using (true) with check (true)';
  end if;
end $$;

-- ---------- Row Level Security (classroom demo only: anon key can read/write all) ----------
alter table public.users             enable row level security;
alter table public.audit_log         enable row level security;
alter table public.subjects          enable row level security;
alter table public.grade_components  enable row level security;
alter table public.attendance        enable row level security;
alter table public.announcements     enable row level security;
alter table public.assignments       enable row level security;
alter table public.submissions       enable row level security;

drop policy if exists "demo_all" on public.users;
drop policy if exists "demo_all" on public.audit_log;
drop policy if exists "demo_all" on public.subjects;
drop policy if exists "demo_all" on public.grade_components;
drop policy if exists "demo_all" on public.attendance;
drop policy if exists "demo_all" on public.announcements;
drop policy if exists "demo_all" on public.assignments;
drop policy if exists "demo_all" on public.submissions;

create policy "demo_all" on public.users             for all using (true) with check (true);
create policy "demo_all" on public.audit_log         for all using (true) with check (true);
create policy "demo_all" on public.subjects          for all using (true) with check (true);
create policy "demo_all" on public.grade_components  for all using (true) with check (true);
create policy "demo_all" on public.attendance        for all using (true) with check (true);
create policy "demo_all" on public.announcements     for all using (true) with check (true);
create policy "demo_all" on public.assignments       for all using (true) with check (true);
create policy "demo_all" on public.submissions       for all using (true) with check (true);

-- ---------- create a test admin ONLY if the database has no admin yet ----------
insert into public.users (id, name, role, password)
select 'A001', 'Administrator', 'ADMIN', 'admin123'
where not exists (select 1 from public.users where role = 'ADMIN');

-- ---------- make Supabase notice the new tables right away ----------
notify pgrst, 'reload schema';

select
  to_regclass('public.users') as users_table,
  to_regclass('public.subjects') as subjects_table,
  to_regclass('public.grade_components') as grades_table,
  to_regclass('public.attendance') as attendance_table,
  to_regclass('public.assignments') as assignments_table,
  to_regclass('public.announcements') as announcements_table,
  to_regclass('public.submissions') as submissions_table;