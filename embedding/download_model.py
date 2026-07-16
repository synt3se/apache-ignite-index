import os
from sentence_transformers import SentenceTransformer

model_name = "clip-ViT-B-32"
save_path = "./models/clip-ViT-B-32"

print(f"Скачивание модели {model_name}...")
model = SentenceTransformer(model_name)

print(f"Сохранение модели локально по пути: {save_path}...")
model.save(save_path)

print("Готово! Теперь интернет для этой модели больше не нужен.")