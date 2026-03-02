# Setting up a GitHub repo for a Minecraft plugin

Step-by-step guide to put a plugin on GitHub with CI (build on PRs) and releases (tag → build → publish with release notes). Assumes you have a Maven-based plugin and a **new or empty** GitHub repo.

---

## 1. Create the repo on GitHub

- Go to [GitHub](https://github.com/new), create a new repository.
- Name it (e.g. `my-plugin`). Leave it **empty** (no README, no .gitignore).
- Copy the repo URL (e.g. `https://github.com/YourName/my-plugin.git`).

---

## 2. Initialize Git and add the remote

From your plugin project root:

```bash
git init
git remote add origin https://github.com/YourName/your-repo.git
```

Set your identity (use your email and GitHub username):

```bash
git config user.email "your@email.com"
git config user.name "YourGitHubUsername"
```

---

## 3. Add a .gitignore

Create or update `.gitignore` in the project root so build output and IDE files are not committed:

```
# Build
target/

# IDE
.idea/
*.iml
.vscode/
.settings/
.project
.classpath

# Logs / crash
*.log
hs_err_pid*.log
replay_pid*.log

# OS
.DS_Store
Thumbs.db
```

---

## 4. GitHub Actions: build on pull requests

Create `.github/workflows/build.yml`:

```yaml
name: Build

on:
  pull_request:
    branches: [ main, master ]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven

      - name: Build with Maven
        run: mvn -B clean package

      - name: Upload JAR artifact
        uses: actions/upload-artifact@v4
        with:
          name: plugin-jar
          path: target/*.jar
```

- Adjust `path: target/*.jar` if your JAR is built elsewhere (e.g. `target/your-artifactId-*.jar`).
- Build runs only on **pull requests**, so pushing a tag for a release does not run this workflow (avoids double build).

---

## 5. GitHub Actions: release on tag

Create `.github/workflows/release.yml`:

```yaml
name: Release

on:
  push:
    tags:
      - 'v*'

jobs:
  release:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven

      - name: Build with Maven
        run: mvn -B clean package

      - name: Get JAR path
        id: jar
        run: |
          JAR=$(ls target/*.jar | head -1)
          echo "path=$JAR" >> $GITHUB_OUTPUT

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          files: ${{ steps.jar.outputs.path }}
          body_path: RELEASE_INFO.md
          replace_existing: true
          draft: false
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

- **`body_path: RELEASE_INFO.md`** – release description is taken from that file (see below).
- **`replace_existing: true`** – re-pushing the same tag updates the existing release instead of failing.

Adjust `ls target/*.jar` if your JAR name pattern is different (e.g. `target/myplugin-*.jar`).

---

## 6. Release notes file (RELEASE_INFO.md)

Create `RELEASE_INFO.md` in the project root. This file is used as the **body** of every GitHub Release (Minecraft version, compatibility, etc.):

```markdown
## MyPlugin 1.0.0

## Minecraft version

- **Supported / tested:** 1.21.4
- **Spigot/Paper:** 1.21.4

## Compatibility

- Built for Spigot API 1.21.4. Use on 1.21.4 servers.
```

- Edit this file whenever you change supported MC version or add compatibility notes.
- Do **not** rely on pom for the release description; keep it in this file so you control what users see.

---

## 7. First commit and push

```bash
git add -A
git commit -m "Initial commit: plugin with CI and release workflows"
git branch -M main
git push -u origin main
```

---

## 8. Pushing a release

1. **Bump version** in `pom.xml` (e.g. `<version>1.0.0</version>`).
2. **Update** `RELEASE_INFO.md` (plugin version heading, MC version, compatibility).
3. **Commit and push** (optional but recommended so `main` is up to date):
   ```bash
   git add pom.xml RELEASE_INFO.md
   git commit -m "Release 1.0.0"
   git push origin main
   ```
4. **Tag and push the tag** (this triggers the release):
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

The Release workflow will build the JAR and create (or update) the GitHub Release for that tag with the JAR attached and the body from `RELEASE_INFO.md`.

---

## 9. Updating an existing release

If you need to change the release body or JAR for the same version (e.g. fix release notes or rebuild):

1. Edit `RELEASE_INFO.md` (and/or code), commit, push `main`.
2. Move the tag to the new commit and force-push:
   ```bash
   git tag -f v1.0.0
   git push origin v1.0.0 --force
   ```
With `replace_existing: true`, the existing v1.0.0 release will be updated (new body and new JAR).

---

## 10. Checklist summary

- [ ] GitHub repo created (empty).
- [ ] `git init`, `remote add origin`, `user.email` / `user.name` set.
- [ ] `.gitignore` in place (target/, IDE, logs).
- [ ] `.github/workflows/build.yml` – build on PR only.
- [ ] `.github/workflows/release.yml` – release on tag `v*`, `body_path: RELEASE_INFO.md`, `replace_existing: true`.
- [ ] `RELEASE_INFO.md` – plugin version, Minecraft version, compatibility.
- [ ] First push: `git add -A`, commit, `git branch -M main`, `git push -u origin main`.
- [ ] For each release: bump pom, update RELEASE_INFO.md, commit, tag `vX.Y.Z`, `git push origin vX.Y.Z`.
