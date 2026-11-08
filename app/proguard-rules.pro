# Release builds don't minify by default in this project (see app/build.gradle.kts).
# If you turn isMinifyEnabled on later, Room and NanoHTTPD are the two
# libraries here most likely to need explicit -keep rules; consult each
# library's own consumer-rules first, since both usually ship their own.
