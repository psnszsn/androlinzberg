# azk-platform test key

A committed, public test signing key (RSA-2048, self-signed) in the mold of
AOSP's `build/target/product/security/platform.{pk8,x509.pem}`. It signs
`framework-res.apk` (package "android") and the build-time fixture APKs, so
signature-protected permission grants resolve through the real
SigningDetails-match path in the hosted package manager. It carries no trust
outside a hosted session; never use it for anything distributed.

`azk-untrusted.{pk8,x509.pem}` is a second committed, public RSA test key.
It signs only the caller-identity fixtures, which must not inherit the
platform-signature exception. It carries no production trust.
