-- Darknes ID device registry
create table if not exists public.darknes_devices (
    user_id uuid not null references auth.users(id) on delete cascade,
    device_id text not null,
    device_name text not null default 'Android device',
    platform text not null default 'Android',
    created_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    primary key (user_id, device_id)
);

alter table public.darknes_devices enable row level security;

drop policy if exists "Users can view their own devices" on public.darknes_devices;
create policy "Users can view their own devices"
on public.darknes_devices for select
to authenticated
using (auth.uid() = user_id);

drop policy if exists "Users can register their own devices" on public.darknes_devices;
create policy "Users can register their own devices"
on public.darknes_devices for insert
to authenticated
with check (auth.uid() = user_id);

drop policy if exists "Users can update their own devices" on public.darknes_devices;
create policy "Users can update their own devices"
on public.darknes_devices for update
to authenticated
using (auth.uid() = user_id)
with check (auth.uid() = user_id);

drop policy if exists "Users can delete their own device records" on public.darknes_devices;
create policy "Users can delete their own device records"
on public.darknes_devices for delete
to authenticated
using (auth.uid() = user_id);

create index if not exists darknes_devices_user_last_seen_idx
on public.darknes_devices (user_id, last_seen_at desc);
