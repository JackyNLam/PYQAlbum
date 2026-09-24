import os
import sys
import json
import re
import subprocess
from pathlib import Path
from typing import List, Dict
import dashscope
from dashscope import MultiModalConversation
import imagehash
from PIL import Image

# ================= 配置區 =================
PHASH_THRESHOLD = 15  # pHash 漢明距離閾值
PENALTY_FACTOR = 0.1 # 相似圖片的分數懲罰倍率

dashscope.base_http_api_url = 'https://dashscope-intl.aliyuncs.com/api/v1'
API_KEY = os.getenv('DASHSCOPE_API_KEY')
MODEL_NAME =os.getenv('PIC_MODEL')

SCRIPT_DIR = Path(__file__).resolve().parent
IMAGE_DIR = str(SCRIPT_DIR.parent / "photo")
RESIZED_DIR = str(SCRIPT_DIR.parent / "photo" / "resized_photos")

BATCH_SIZE = 10
TOP_N = 1000
# ==========================================


def step1_resize_images(src_dir: str, dst_dir: str) -> List[str]:
    """步驟1: 使用 ImageMagick 將所有圖片 resize 到 800x"""
    src_path = Path(src_dir)
    if not src_path.exists():
        print(f"❌ 圖片資料夾不存在: {src_dir}")
        sys.exit(1)

    Path(dst_dir).mkdir(parents=True, exist_ok=True)

    valid_exts = {'.jpg', '.jpeg', '.png', '.webp'}
    image_files = []

    for f in sorted(src_path.iterdir()):
        if f.suffix.lower() in valid_exts and f.is_file():
            dst_path = Path(dst_dir) / f.name
            cmd = [
                "convert", str(f),
                "-resize", "800x>",
                "-quality", "80",
                str(dst_path)
            ]
            try:
                subprocess.run(cmd, check=True, capture_output=True)
                image_files.append(str(dst_path))
                print(f"  ✅ Resized: {f.name}")
            except subprocess.CalledProcessError as e:                print(f"  ❌ Failed: {f.name} - {e.stderr.decode()}")
            except FileNotFoundError:
                print("❌ 找不到 'convert' 命令，請確認已安裝 ImageMagick")
                sys.exit(1)

    print(f"\n📁 共處理 {len(image_files)} 張圖片\n")
    return image_files


def batch_score_images(image_paths: List[str]) -> List[Dict]:
    """分批發送圖片給 AI 評分（修復檔名幻覺問題 + 自動重試未評分圖片）"""
    all_results = []
    scored_files = set()  # ✅ 新增：記錄已成功評分的檔案名稱
    prompt = """你是一位資深攝影編輯和社交媒體專家。
請對以下圖片進行「朋友圈發布適合度」評分（1-100分），評估標準：
構圖與美感（光影、色彩、主體突出）
視覺衝擊力與故事感
社交媒體吸引力（是否讓人想點讚/停留）
技術品質（清晰度、曝光、白平衡）

⚠️ 嚴格要求：請以純 JSON Array 格式回覆，不要包含任何其他文字。
注意：陣列中的每個物件必須嚴格按照圖片輸入順序排列。
你不需要返回 file 欄位，只需返回 score 和 reason：
[{ "score": 85.5, "reason": "簡短理由"}, { "score": 70.0, "reason": "簡短理由"}]"""

    total_batches = (len(image_paths) + BATCH_SIZE - 1) // BATCH_SIZE
    
    # ✅ 修改：使用 while 循環或額外處理隊列，這裡採用最小改動方式
    pending_images = list(image_paths) 
    processed_count = 0
    
    while processed_count < len(image_paths):
        # 取出當前要處理的批次
        current_batch_size = min(BATCH_SIZE, len(pending_images))
        if current_batch_size == 0:
            break
            
        batch = pending_images[:current_batch_size]
        # 從待處理列表中移除當前批次
        pending_images = pending_images[current_batch_size:] 
        
        batch_num = (processed_count // BATCH_SIZE) + 1
        print(f"🤖 評分批次 {batch_num} ({len(batch)} 張)...")

        content = [{"image": p} for p in batch]
        content.append({"text": prompt})
        messages = [{"role": "user", "content": content}]

        try:
            response = MultiModalConversation.call(
                api_key=API_KEY,
                model=MODEL_NAME,
                max_tokens=8192,
                messages=messages
            )

            if response.status_code != 200:
                print(f"  ❌ API Error: {response.code} - {response.message}")
                # ✅ API 錯誤時，將整個批次放回待處理隊列尾部
                pending_images.extend(batch)
                processed_count += len(batch) # 避免死循環，但實際上這些圖片沒被成功處理
                continue

            raw_text = response.output.choices[0].message.content[0]["text"]
            print(f"  📥 Raw: {raw_text[:200]}...")

            json_match = re.search(r'\[[\s\S]*\]', raw_text)
            if not json_match:
                print(f"  ⚠️ 未找到 JSON Array，將此批次圖片移至下一輪")
                pending_images.extend(batch)
                processed_count += len(batch)
                continue

            scores = json.loads(json_match.group())

            batch_scored_count = 0
            for idx, item in enumerate(scores):
                if idx < len(batch):
                    real_filename = Path(batch[idx]).name
                    score_val = item.get("score", 0)
                    reason_val = item.get("reason", "N/A")

                    all_results.append({
                        "file": real_filename,
                        "score": score_val,
                        "reason": reason_val
                    })
                    scored_files.add(real_filename)
                    batch_scored_count += 1
                    print(f"     ✅ {real_filename}: {score_val}分")
                else:
                    print(f"     ⚠️ AI 返回結果數量超過批次圖片數，忽略多餘項")

            # ✅ 核心修改：將本批次中「未獲得評分」的圖片加回 pending_images
            if len(scores) < len(batch):
                missing_paths = [batch[j] for j in range(len(scores), len(batch))]
                missing_names = [Path(p).name for p in missing_paths]
                print(f"     ⚠️ 以下圖片未獲得評分，將移至下一批次: {missing_names}")
                pending_images.extend(missing_paths)
            
            processed_count += batch_scored_count

        except json.JSONDecodeError as e:
            print(f"  ⚠️ JSON 解析失敗 ({e}):\n{raw_text[:300]}")
            pending_images.extend(batch)
            processed_count += len(batch)
        except Exception as e:
            print(f"  ❌ 批次處理異常: {type(e).__name__}: {e}")
            pending_images.extend(batch)
            processed_count += len(batch)

    return all_results


def deduplicate_and_rank(scored_items: List[Dict], top_n: int) -> List[Dict]:
    """去重 + 排序輸出"""
    ranked = sorted(scored_items, key=lambda x: x.get("score", 0), reverse=True)
    selected = ranked[:top_n]

    print("\n" + "=" * 60)
    print(f"🏆 TOP {len(selected)} 朋友圈精選圖片（由佳至差）")
    print("=" * 60)
    for idx, item in enumerate(selected, 1):
        print(f"  #{idx:2d} | {item['score']:5.1f}分 | {item['file']}")
        print(f"       💬 {item.get('reason', 'N/A')}")
    print("=" * 60)
    return selected


def apply_phash_penalty(scored_items: List[Dict], threshold: int = PHASH_THRESHOLD, penalty: float = PENALTY_FACTOR) -> List[Dict]:
    """
    使用 pHash 找出高度相似圖片，只保留最高分者原分數，
    其餘相似圖片分數 x penalty。
    """
    if not scored_items:
        return scored_items

    print(f"\n🔍 Step 3: pHash 相似度檢測 (threshold={threshold}, penalty={penalty})...")

    # 預計算所有圖片的 pHash
    hash_map: Dict[str, imagehash.ImageHash] = {}
    for item in scored_items:
        img_path = Path(RESIZED_DIR) / item["file"]
        try:
            h = imagehash.phash(Image.open(img_path))
            hash_map[item["file"]] = h
        except Exception as e:
            print(f"  ⚠️ 無法讀取 {item['file']} 計算 pHash: {e}")

    # 按分數降序排列，確保高分優先被選為「代表」
    sorted_items = sorted(scored_items, key=lambda x: x.get("score", 0), reverse=True)
    penalized_files = set()  # 記錄已被懲罰的檔案

    for i, item_a in enumerate(sorted_items):
        file_a = item_a["file"]
        if file_a not in hash_map or file_a in penalized_files:
            continue

        for j in range(i + 1, len(sorted_items)):
            item_b = sorted_items[j]
            file_b = item_b["file"]
            if file_b not in hash_map or file_b in penalized_files:
                continue

            dist = hash_map[file_a] - hash_map[file_b]
            if dist <= threshold:
                original_score = item_b["score"]
                item_b["score"] = round(original_score * penalty, 2)
                item_b["reason"] = f"[pHash相似(dist={dist})] {item_b.get('reason', '')}"
                penalized_files.add(file_b)
                print(f"  🔻 {file_b}: {original_score} → {item_b['score']} (相似於 {file_a})")

    print(f"  ✅ pHash 檢測完成，共懲罰 {len(penalized_files)} 張相似圖片\n")
    return scored_items

def send_telegram_notification(selected_items: List[Dict]):
    """使用 openclaw 發送 Top N 精選圖片結果到 Telegram"""
    if not selected_items:
        print("⚠️ 沒有精選圖片可發送")
        return

    # 構建消息內容
    lines = ["🏆 朋友圈精選圖片 TOP {}:".format(len(selected_items))]
    for idx, item in enumerate(selected_items, 1):
        score = item.get('score', 0)
        filename = item.get('file', 'Unknown')
        reason = item.get('reason', '')
        lines.append(f"#{idx} [{score:.1f}分] {filename}")
        lines.append(f"   💬 {reason}")
    
    msg = "\n".join(lines)

    # 調用 openclaw 發送（基於你提供的模板）
    try:
        subprocess.run(
            ["/home/admin/.local/share/pnpm/openclaw", "message", "send",
             "--target", "752979273",
             "--account", "@myClawTask_bot",
             "--message", msg],
            check=False,
            capture_output=True  # 避免輸出干擾主流程日誌
        )
        print("✅ Telegram 通知已發送")
    except FileNotFoundError:
        print("❌ 找不到 'openclaw' 命令，請確認已安裝並配置 PATH")
    except Exception as e:
        print(f"❌ Telegram 發送失敗: {e}")

def rename_top_images(selected_items: List[Dict], src_dir: str):
    """
    根據排名為精選圖片重新命名
    例如: img9922.jpg (第1名) -> 01_img9922.jpg
    若已有排名前綴，則替換為最新排名
    """
    if not selected_items:
        print("⚠️ 沒有精選圖片可重命名")
        return
    
    print(f"\n🏷️ Step 5: 按排名重命名 TOP {len(selected_items)} 圖片...")
    renamed_count = 0
    # ✅ 新增：匹配已有的數字排名前綴（如 01_, 99_, 001_ 等）
    rank_prefix_pattern = re.compile(r'^\d+_')
    
    for idx, item in enumerate(selected_items, 1):
        original_name = item.get("file", "")
        if not original_name:
            continue
            
        prefix = f"{idx:02d}_"
        
        # ✅ 修改：如果已有排名前綴，先移除舊前綴再拼上新前綴
        base_name = rank_prefix_pattern.sub('', original_name)
        new_name = f"{prefix}{base_name}"
        
        # 如果新舊檔名相同，無需操作
        if new_name == original_name:
            print(f"  ⏭️ {original_name} 排名未變，跳過")
            continue
            
        src_path = Path(src_dir) / original_name
        dst_path = Path(src_dir) / new_name
        
        try:
            if src_path.exists():
                src_path.rename(dst_path)
                print(f"  ✅ {original_name} → {new_name}")
                renamed_count += 1
            else:
                print(f"  ⚠️ 找不到檔案: {src_path}")
        except Exception as e:
            print(f"  ❌ 重命名失敗 {original_name}: {e}")
            
    print(f"  🏁 共重命名 {renamed_count} 張圖片\n")


def main():
    if not API_KEY:
        print("❌ 請設定環境變數 DASHSCOPE_API_KEY")
        sys.exit(1)

    print(f"📂 圖片來源: {IMAGE_DIR}")
    print("📐 Step 1: 縮放圖片至 800px...")
    images = step1_resize_images(IMAGE_DIR, RESIZED_DIR)

    if not images:
        print("❌ 未找到任何圖片")
        sys.exit(1)

    print("🧠 Step 2: AI 智能評分中...")
    scored = batch_score_images(images)

    if not scored:
        print("❌ 未取得任何評分結果")
        sys.exit(1)

    scored = apply_phash_penalty(scored)
    top_selected = deduplicate_and_rank(scored, TOP_N)
    send_telegram_notification(top_selected)
    #rename according to ranking
    rename_top_images(top_selected, RESIZED_DIR)
    rename_top_images(top_selected, IMAGE_DIR)

if __name__ == "__main__":
    main()
