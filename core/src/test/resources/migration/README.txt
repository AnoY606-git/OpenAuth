Синтетические fixtures: два URI составляют batch из двух частей с batch_id=42.
Секрет — публичная последовательность ASCII «01234567890123456789»;
адреса используют зарезервированный example.invalid. Реальных аккаунтов нет.
Часть 0: TOTP SHA1, 6 цифр, alice@example.invalid.
Часть 1: HOTP SHA1, 6 цифр, bob@example.invalid, counter=9223372036854775807.
Данные созданы независимо небольшим кодировщиком wire format protobuf,
исходный код сторонних реализаций не заимствован. Схема сверена с:
https://github.com/beemdevelopment/Aegis/blob/master/app/src/main/proto/google_auth.proto
https://github.com/qistoph/otp_export/blob/master/OtpMigration.proto
