# Часть API androidx.webkit вызывается через рефлексию (UserAgentMetadata,
# X-Requested-With), поэтому не даём R8 их вырезать или переименовать.
-keep class androidx.webkit.** { *; }
-dontwarn androidx.webkit.**
