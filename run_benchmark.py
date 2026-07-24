import os
import re
import csv
import shutil
import subprocess
import time
from datetime import datetime

# ================= НАСТРОЙКИ =================

# Пути к файлам (os.path.normpath делает пути кроссплатформенными)
XML_FILE_PATH = os.path.normpath("docker/config/ignite-config.xml")
CSV_FILE_PATH = "benchmark_results.csv"

# Настройки перебираемого параметра
TARGET_PARAMETER = "m"  # Имя параметра, который мы ищем в XML
PARAMETER_VALUES = [8, 16, 24, 32, 40, 48]  # Значения для перебора

# Команда запуска Docker
DOCKER_CMD = ["docker", "compose", "--profile", "bench", "run", "--rm", "bench"]

# ================= РЕГУЛЯРНЫЕ ВЫРАЖЕНИЯ =================

# Паттерны для извлечения метрик из вывода (stdout)
METRIC_PATTERNS = {
    'Index_type': r'Index type:\s*(.+)',
    'load_and_build_ms': r'load_and_build_ms:\s*([\d.]+)',
    'total_search_ms': r'total_search_ms:\s*([\d.]+)',
    'avg_search_ms': r'avg_search_ms:\s*([\d.]+)',
    'p95_search_ms': r'p95_search_ms:\s*([\d.]+)',
    'p99_search_ms': r'p99_search_ms:\s*([\d.]+)',
    'measured_queries': r'measured_queries:\s*(\d+)',
    'qps': r'qps:\s*([\d.]+)',
    'Recall_10': r'Recall@10:\s*([\d.]+)%',
    'DistanceRecall_10': r'DistanceRecall@10:\s*([\d.]+)%',
    'Perfect_ID_queries': r'Perfect ID queries:\s*([\d/]+)',
    'Perfect_distance_queries': r'Perfect distance queries:\s*([\d/]+)',
    'Avg_distance_error': r'Average distance error:\s*([\d.e+-]+)',
    'Max_distance_error': r'Maximum distance error:\s*([\d.e+-]+)'
}

def modify_xml_parameter(file_path, param_name, new_value):
    """Изменяет значение параметра в XML файле с помощью Regex для сохранения форматирования."""
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()

    # Ищет: <property name="PARAM_NAME" value="ANYTHING"/>
    # Группы: 1 - до value=", 2 - старое значение, 3 - после значения до конца тега
    pattern = re.compile(rf'(<property\s+name="{param_name}"\s+value=")([^"]+)("[\s/]*>)')
    
    if not pattern.search(content):
        raise ValueError(f"Параметр {param_name} не найден в файле {file_path}")

    # Заменяем старое значение на новое
    new_content = pattern.sub(rf'\g<1>{new_value}\g<3>', content)

    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(new_content)

def parse_benchmark_output(output):
    """Парсит вывод консоли и возвращает словарь с метриками."""
    results = {}
    for metric_name, regex_pattern in METRIC_PATTERNS.items():
        match = re.search(regex_pattern, output)
        if match:
            results[metric_name] = match.group(1).strip()
        else:
            results[metric_name] = "ERROR" # Если метрика не найдена в логах
    return results

def save_to_csv(data_dict, filepath):
    """Сохраняет результаты в CSV. Если файла нет, создает заголовки."""
    file_exists = os.path.isfile(filepath) and os.path.getsize(filepath) > 0
    
    with open(filepath, mode='a', newline='', encoding='utf-8') as csvfile:
        writer = csv.DictWriter(csvfile, fieldnames=data_dict.keys())
        if not file_exists:
            writer.writeheader()
        writer.writerow(data_dict)

def main():
    if not os.path.exists(XML_FILE_PATH):
        print(f"Ошибка: Файл {XML_FILE_PATH} не найден. Запустите скрипт из корня проекта.")
        return

    # Делаем бэкап оригинального конфига
    backup_path = f"{XML_FILE_PATH}.bak"
    shutil.copy2(XML_FILE_PATH, backup_path)
    print(f"Бэкап конфигурации сохранен в {backup_path}\n")

    try:
        for value in PARAMETER_VALUES:
            print(f"[{datetime.now().strftime('%H:%M:%S')}] Итерация: {TARGET_PARAMETER} = {value}")

            # Шаг 1: Изменяем конфиг
            modify_xml_parameter(XML_FILE_PATH, TARGET_PARAMETER, value)
            print(f" -> Конфиг обновлен")

            # Шаг 1.5: Перезапуск кластера Ignite
            print(" -> Остановка старого кластера и очистка данных...")
            subprocess.run(["docker", "compose", "down", "-v"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

            print(" -> Запуск кластера с новым конфигом...")
            # Запускаем только фоновые сервисы (без профиля bench)
            subprocess.run(["docker", "compose", "up", "-d"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

            print(" -> Ожидание инициализации узлов (15 секунд)...")
            time.sleep(15)  # Подберите время, нужное вашим нодам для старта

            # Шаг 2: Запуск бенчмарка
            print(f" -> Запуск бенчмарка (ожидание завершения...)")
            process = subprocess.run(
                DOCKER_CMD,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True
            )

            if process.returncode != 0:
                print(f" -> ВНИМАНИЕ: Docker завершился с ошибкой (код {process.returncode})")
            
            # Шаг 3: Парсинг вывода
            metrics = parse_benchmark_output(process.stdout)
            
            # Добавляем инфу о параметре на первое место
            row_data = {f"param_{TARGET_PARAMETER}": value}
            row_data.update(metrics)

            # Шаг 4: Сохранение
            save_to_csv(row_data, CSV_FILE_PATH)
            print(f" -> Результаты сохранены: QPS = {metrics.get('qps')}, Recall = {metrics.get('Recall_10')}%\n")

    except KeyboardInterrupt:
        print("\nПроцесс прерван пользователем.")
    except Exception as e:
        print(f"\nПроизошла ошибка: {e}")
    finally:
        # Восстанавливаем оригинальный конфиг после завершения/ошибки
        shutil.move(backup_path, XML_FILE_PATH)
        print(f"Оригинальный файл конфигурации {XML_FILE_PATH} восстановлен.")
        print(f"Все результаты записаны в {CSV_FILE_PATH}")

if __name__ == "__main__":
    main()