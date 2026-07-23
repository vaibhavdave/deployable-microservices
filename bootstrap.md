# bootstrap.md — From an Empty Directory to a Runnable, Empty Spring Boot Skeleton

This document is the missing "step zero" before `learner.md`: it walks through exactly how a Spring Boot service's project structure gets created — starting from an empty directory, ending the moment the project compiles and boots with **zero business logic**. Nothing here is product-service/order-service/api-gateway-specific business code; it's the mechanical scaffolding every one of those three modules starts from. `product-service` is used as the concrete worked example since it's the first module this repo actually built — the same sequence applies to any new service module added later.

**Where this stops:** the last step below is a generated `@SpringBootApplication` class with an empty `main()` and nothing else — no entity, no repository, no controller. The first line of actual application code (the `Product` entity) is `learner.md` Step 2, not this document.

## Prerequisites

| Tool | Version used in this repo | Why |
|---|---|---|
| JDK | 21 | Spring Boot 3.x requires 17+; this repo standardizes on 21 |
| Gradle | 8.14.3 | Build tool (per the user's requirement — not Maven) |
| A shell + git | any recent | repo/version control |

Verify before starting:
```
java -version    # should report 21
gradle -v        # any locally installed Gradle 8.x is enough to generate the wrapper
```
You do not need Gradle installed system-wide long-term — Step 5 below generates a wrapper (`./gradlew`) that pins the exact version for everyone who clones the repo. A local Gradle install is only needed once, to generate that wrapper.

## Step 1 — Initialize the Repository Root

```
mkdir deployable-microservices && cd deployable-microservices
git init
```
Nothing Spring-specific yet — just a version-controlled empty directory.

## Step 2 — Decide the Module Layout Before Writing Any Config

This repo is a **multi-module Gradle build**: one root project, three Spring Boot application modules underneath it (`product-service`, `order-service`, `api-gateway`), each independently buildable and independently deployable. Decide this up front because it determines the directory layout every later step assumes:

```
deployable-microservices/        <- root project (no application code of its own)
  settings.gradle
  build.gradle                   <- shared config only (Java version, plugin versions)
  services/
    product-service/             <- one Gradle module, one Spring Boot app
    order-service/
    api-gateway/
```
A single-module project would just skip the `services/<name>/` nesting and put everything at the root — the steps below are identical either way, just at a different path.

## Step 3 — Declare the Module in `settings.gradle`

Before a module directory has *any* files in it, tell Gradle it exists:
```groovy
rootProject.name = 'deployable-microservices'
include 'services:product-service'
```
(`order-service` and `api-gateway` get their own `include` lines the same way, added when each of those modules is bootstrapped — not needed yet for a single-module walkthrough.) Without this line, Gradle has no idea `services/product-service` is a project — running any Gradle command against it later would fail with "not found," not a helpful error about missing code.

## Step 4 — Root `build.gradle`: Shared Config, No Application Code

The root `build.gradle` never contains business logic — only configuration shared across every module:
```groovy
plugins {
    id 'org.springframework.boot' version '3.2.5' apply false
    id 'io.spring.dependency-management' version '1.1.4' apply false
    id 'java'
}

allprojects {
    group = 'com.bookstore'
    version = '0.1.0'
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply plugin: 'java'
    apply plugin: 'io.spring.dependency-management'

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }
}
```
`apply false` on the Spring Boot / dependency-management plugins means: make the plugin *available* to every module, but don't actually activate it at the root (the root itself isn't a Spring Boot application). Each module's own `build.gradle` (Step 9) is what applies them for real.

## Step 5 — Generate the Gradle Wrapper

```
gradle wrapper --gradle-version 8.14.3
```
This produces `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.{jar,properties}` — commit all four. From this point on, everyone (and CI) uses `./gradlew`, never a locally-installed `gradle`, so the build is reproducible regardless of what's on any given machine.

*If your network blocks the distribution-URL reachability check* (some sandboxed/CI environments do — this repo's own build environment hit exactly this against `services.gradle.org`'s redirect to GitHub release assets), generate with `gradle wrapper --gradle-version 8.14.3 --no-validate-url` instead. The wrapper files themselves are identical either way; only the immediate connectivity check is skipped. The first real `./gradlew` invocation on a normal machine still downloads and verifies the pinned distribution.

## Step 6 — `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx1024m
org.gradle.caching=true
org.gradle.parallel=true
```
Not required for a project to work, but worth setting before the module count grows — parallel + cached builds matter more once there are three-plus modules than when there's one.

## Step 7 — Scaffold the Module's Directory Tree

Still zero Spring code — just the standard Gradle/Maven-style Java source layout, empty:
```
mkdir -p services/product-service/src/main/java
mkdir -p services/product-service/src/main/resources
mkdir -p services/product-service/src/test/java
```
Gradle's Java plugin (applied in Step 4) expects exactly this layout by convention (`src/main/java`, `src/main/resources`, `src/test/java`) — deviating from it means fighting the build tool with custom `sourceSets` config for no benefit.

## Step 8 — Decide the Base Package

Before creating any `.java` file, decide the package name, because it fixes the subdirectory path every class lives under from here on: this repo uses `com.bookstore.product` for product-service (`com.bookstore.order` for order-service, `com.bookstore.gateway` for api-gateway) — one sub-package per service under a shared `com.bookstore` root, reflecting that these are independently-deployable services sharing an organizational namespace, not one application.

```
mkdir -p services/product-service/src/main/java/com/bookstore/product
mkdir -p services/product-service/src/test/java/com/bookstore/product
```

## Step 9 — The Module's Own `build.gradle`

This is where the module actually becomes "a Spring Boot application" rather than just a plain Java module — apply the plugins Step 4 made available:
```groovy
plugins {
    id 'org.springframework.boot'
    id 'io.spring.dependency-management'
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
}
```
`spring-boot-starter-web` alone is enough to produce a bootable application with an embedded server — that's the true minimum for "does this thing start." (This repo's actual `product-service/build.gradle` also adds `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, the Postgres driver, and Flyway at this same point, in direct anticipation of the business code coming in `learner.md` Step 2 — that's a decision about the *next* step, not part of bootstrapping the skeleton itself. A brand-new module with no planned persistence layer yet would genuinely stop at just `spring-boot-starter-web`.)

## Step 10 — The Minimal `@SpringBootApplication` Class

The one piece of "code" this document includes — and it's boilerplate, not business logic:
```java
package com.bookstore.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ProductServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}
```
at `services/product-service/src/main/java/com/bookstore/product/ProductServiceApplication.java`. There is deliberately no entity, repository, service, or controller yet — just enough for Spring Boot's component scan to have a root package (`com.bookstore.product`) and for the application to be bootable.

An empty `src/main/resources/application.yml` (even a zero-byte file, or just `spring.application.name: product-service`) is worth adding here too, so the next step's log output identifies itself clearly instead of falling back to Spring Boot's default unnamed-application banner.

## Step 11 — Verify the Skeleton, Before Writing Anything Else

Two checks, in order, before a single line of business logic is written:

1. **Does it compile?**
   ```
   ./gradlew :services:product-service:compileJava
   ```
2. **Does it boot?**
   ```
   ./gradlew :services:product-service:bootRun
   ```
   Expect a normal Spring Boot startup banner and log lines ending in `Started ProductServiceApplication in N seconds`, with an embedded Tomcat listening on port 8080 (the default — `server.port: 8081` etc. gets set once `application.yml` is fleshed out in `learner.md` Step 2). Ctrl-C to stop it.

If both pass, the skeleton is proven sound *before* any domain logic is layered on top of it — exactly the same reasoning as `learner.md`'s Step 7 (validate the whole system end-to-end before Helm/Istio): isolate "does the scaffolding work" from "does my code work," and don't debug both at once.

## Where This Hands Off

This is the full extent of "bootstrap" — from here, `learner.md` Step 2 begins writing the actual `Product` entity, `ProductRepository`, `ProductService`, and `ProductController`. Repeating Steps 3 and 7–11 (using the appropriate base package) is exactly how `order-service` and `api-gateway` were each subsequently bootstrapped in this repo, before their own business code (Steps 3–4 of `learner.md`) was written.
