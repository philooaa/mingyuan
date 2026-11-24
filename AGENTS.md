# Local service startup

For requests to start this project's local MySQL, Redis, backend and management frontend, use `scripts/start_local.ps1` and follow `docs/本地服务启动说明.md`. Check service readiness and report the results. The launcher leaves existing services running and uses the most recently packaged backend, which may not include subsequent source changes. Do not store database passwords in the repository or initialize or replace the existing database as part of startup.
