# Сторонние компоненты

Значок TTWR SQLite использует перо из [официального логотипа SQLite](https://sqlite.org/images/sqlite370_banner.svg). Источник, составной SVG и порядок генерации PNG/ICO описаны в [assets/README.md](assets/README.md). Исходная геометрия пера сохранена, изменены цвет, масштаб и положение.

| Компонент | Версия | Источник |
|---|---|---|
| Оригинальный TTWR | исходный бинарный архив | `vendor/upstream/ttwr.dat` в репозитории; SHA-256 в lock-файле |
| SQLite JDBC | 3.54.1.0 | https://github.com/xerial/sqlite-jdbc |
| SLF4J API / NOP | 1.7.36 | https://www.slf4j.org/ |
| Apache Commons Net | 3.6 | https://commons.apache.org/proper/commons-net/ |
| JavaMail | 1.6.2 | https://javaee.github.io/javamail/ |
| JavaBeans Activation Framework | 1.1.1 | https://javaee.github.io/activation/ |
| ASM, только сборка | 9.7.1 | https://asm.ow2.io/ |
| Eclipse Temurin, полный комплект | 25.0.5 x64 | https://adoptium.net/ |
| Zig, только компиляция EXE | проверено 0.17.1 | https://ziglang.org/ |

Условия и уведомления библиотек находятся в соответствующих JAR и документации их поставщиков. Полный JRE распространяется вместе с его `legal` и `NOTICE`; эти файлы нельзя исключать при упаковке. Zig и ASM для сборки не требуется устанавливать пользователю программы.

Новая общая лицензия на чужие компоненты и исходный TTWR не назначена.
