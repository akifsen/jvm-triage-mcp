# Contributing

Use Java 21 and `./mvnw verify -Pintegration` (`.\mvnw.cmd` on Windows). No Docker required. Generate synthetic recordings; never commit company/personal JFR files. Use the real JDK consumer API and keep each finding tied to event evidence. Apply `spotless:apply` before verification.
