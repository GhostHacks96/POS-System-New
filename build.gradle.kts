plugins {
    id("java")
}

group = "me.ghosthacks96"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.yaml:snakeyaml:2.4")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("com.mysql:mysql-connector-j:9.4.0")
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("io.javalin:javalin:6.7.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.9")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.19.1")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}