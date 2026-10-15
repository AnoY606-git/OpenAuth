# Происхождение компонентов репозитория

Собственный код Open Auth: Copyright (C) 2026 AnoY606-git and Open Auth
contributors; GPL-3.0-only. Текст лицензии: [LICENSE](LICENSE).

| Материал | Источник и авторство | Лицензия и изменения |
| --- | --- | --- |
| `scripts/bootstrap.py` | [SplitBox](https://github.com/AnoY606-git/SplitBox/blob/main/scripts/bootstrap.py), Copyright (C) 2026 AnoY606-git and SplitBox contributors | GPL-3.0-only; адаптирован для инструментов и структуры Open Auth. Исходный заголовок сохранён. |
| `scripts/with-toolchain.sh` | [`scripts/gradle.sh` из SplitBox](https://github.com/AnoY606-git/SplitBox/blob/main/scripts/gradle.sh), Copyright (C) 2026 AnoY606-git and SplitBox contributors | GPL-3.0-only; адаптирован для запуска команд Open Auth. Исходный заголовок сохранён. |
| `gradle/toolchain.json` | [SplitBox](https://github.com/AnoY606-git/SplitBox/blob/main/gradle/toolchain.json), Copyright (C) 2026 AnoY606-git and SplitBox contributors | GPL-3.0-only; конфигурация закреплённых инструментов. Атрибуция сохранена в поле `attribution`. |
| `gradlew` | [Gradle 8.13](https://github.com/gradle/gradle/tree/v8.13.0), Copyright © 2015–2021 the original authors | Apache-2.0; сгенерированный стартовый скрипт Gradle, заголовок сохранён. |
| `gradlew.bat` | [Gradle 8.13](https://github.com/gradle/gradle/tree/v8.13.0), Copyright 2015 the original author or authors | Apache-2.0; сгенерированный стартовый скрипт Gradle, заголовок сохранён. |
| `gradle/wrapper/gradle-wrapper.jar` | [Gradle Wrapper 8.13](https://github.com/gradle/gradle/tree/v8.13.0/platforms/core-runtime/wrapper-main), Gradle contributors | Apache-2.0; бинарный Wrapper. Контрольная сумма закреплена в `gradle/toolchain.json`. |

Зависимости приложения, тестов и сборки описаны в
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Эта атрибуция не изменяет
условия лицензий соответствующих правообладателей.
