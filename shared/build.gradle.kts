plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Modul Kotlin murni (tanpa Android): not, penyusunan notasi, sintesis. Dipakai aplikasi Android dan desktop.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
