# Значок TTWR SQLite

`ttwr-sqlite.svg` сочетает бирюзовые соединённые узлы исходного значка TTWR с пером из логотипа SQLite. `ttwr-sqlite.png` используется в Java-приложении; `ttwr-sqlite.ico` содержит размеры 16, 24, 32, 48, 64, 128 и 256 пикселей для Windows EXE.

Источник пера: элемент `path55` официального [логотипа SQLite](https://sqlite.org/images/sqlite370_banner.svg), копия — `sqlite-logo.svg`. Для составного значка изменены масштаб, положение и цвет пера. Исходный TTWR и SQLite сохраняют своё авторство.

Для повторной генерации из корня репозитория установите `resvg-py` в среду Python и выполните `python scripts/generate-icons.py`. Обычная сборка использует готовые PNG/ICO из репозитория и не требует Python или `resvg-py`.
