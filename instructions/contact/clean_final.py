import pandas as pd
import phonenumbers
import re
import os
import glob

INPUT_DIR = os.path.join(os.path.dirname(__file__), 'csvs')
OUTPUT_FILE = os.path.join(os.path.dirname(__file__), 'cleaned_contacts_final.csv')
DEFAULT_REGION = 'IR'

# Keywords to Identify Creator AND to Strip from Name
# Format: "Keyword": "Creator Name to Assign"
CREATOR_MAP = {
    "sabadbafan": "Sabadbafan",
    "سبد": "Sabadbafan",
    "حیدری": "Heydari",
    "زمان دار": "ZamanDar",
    "برکه": "ZamanDar",
    "حقیقت": "Haghighat",
    "سحر مشتری": "Haghighat",
    "موسوی": "Mousavi",
    "چراغان": "Cheraghan",
    # "شد" is noise, we map it to None so we just strip it, 
    # but don't assign a creator (defaults to RamzArz if no other match)
    "شد": None 
}

def normalize_persian_chars(text):
    """Replaces Arabic style chars with Persian style for consistent matching."""
    if not isinstance(text, str): return text
    translations = {
        'ك': 'ک', 'دِ': 'د', 'بِ': 'ب', 'زِ': 'ز', 'ذِ': 'ذ', 'شِ': 'ش', 'سِ': 'س',
        'ى': 'ی', 'ي': 'ی', '١': '1', '٢': '2', '٣': '3', '٤': '4', '٥': '5',
        '٦': '6', '٧': '7', '٨': '8', '٩': '9', '٠': '0'
    }
    for bad, good in translations.items():
        text = text.replace(bad, good)
    return text

def normalize_phone(phone_raw):
    if not isinstance(phone_raw, str) or not phone_raw.strip():
        return None
    try:
        clean_raw = phone_raw.split(':::')[0].strip()
        parsed_num = phonenumbers.parse(clean_raw, DEFAULT_REGION)
        if not phonenumbers.is_valid_number(parsed_num):
            return None
        return phonenumbers.format_number(parsed_num, phonenumbers.PhoneNumberFormat.E164)
    except phonenumbers.NumberParseException:
        return None

def get_full_name(row):
    first = str(row.get('First Name', '') or '').strip()
    last = str(row.get('Last Name', '') or '').strip()
    
    if first.lower() == 'nan': first = ''
    if last.lower() == 'nan': last = ''
    
    full = f"{first} {last}".strip()
    return normalize_persian_chars(full)

def clean_name_and_find_creator(full_name, row_str):
    """
    1. Identifies Creator based on row_str.
    2. Strips the Creator keywords from the 'full_name' to make it clean.
    """
    # Normalize for searching
    row_lower = normalize_persian_chars(row_str.lower())
    name_clean = full_name
    
    assigned_creator = "RamzArzNegaran" # Default
    
    # Check for keywords
    for keyword, creator_val in CREATOR_MAP.items():
        # 1. Determine Creator (Search in full row)
        if keyword in row_lower:
            if creator_val: # If it's not just noise like "shod"
                assigned_creator = creator_val
        
        # 2. Clean Name (Remove keyword from the Name string)
        # We replace the keyword with empty string, case insensitive
        pattern = re.compile(re.escape(keyword), re.IGNORECASE)
        name_clean = pattern.sub("", name_clean)

    # Post-cleanup on name (remove empty parens, extra spaces, etc)
    # Example: "Ali (Mousavi)" -> removes Mousavi -> "Ali ()" -> cleanup -> "Ali"
    name_clean = name_clean.replace("()", "").replace("[]", "")
    
    # Remove special chars that might be left over like / or - if they are at the end
    name_clean = re.sub(r'^[./\-\s]+|[./\-\s]+$', '', name_clean)
    name_clean = name_clean.strip()

    return name_clean, assigned_creator

def is_garbage_name(cleaned_name):
    """
    Checks if the name (AFTER stripping keywords) is just garbage.
    """
    if not cleaned_name:
        return True
        
    # Remove symbols to check for actual alphanumeric content
    # We allow Persian chars, so we use isalpha() check logic
    chars_only = re.sub(r'[^\w\s]', '', cleaned_name)
    
    # If it's empty after removing symbols (e.g. name was "...")
    if not chars_only.strip():
        return True
        
    # If it is ONLY numbers
    if chars_only.replace(" ", "").isdigit():
        return True
        
    # Length check (e.g. "A", "12")
    if len(chars_only.strip()) < 3:
        return True
        
    return False

def process_contacts():
    # Find all CSV files in the folder
    csv_files = glob.glob(os.path.join(INPUT_DIR, "*.csv"))
    
    if not csv_files:
        print(f"No CSV files found in {INPUT_DIR}")
        return

    print(f"Found {len(csv_files)} CSV files. Merging...")
    
    dfs = []
    for f in csv_files:
        try:
            dfs.append(pd.read_csv(f, dtype=str))
        except Exception as e:
            print(f"Error reading {f}: {e}")
            
    if not dfs:
        print("No data could be read.")
        return

    # Merge all dataframes
    df = pd.concat(dfs, ignore_index=True)
    
    # Remove duplicates before processing
    initial_count = len(df)
    df.drop_duplicates(inplace=True)
    print(f"Total rows after merging: {initial_count}")
    print(f"Total rows after removing exact duplicates: {len(df)}")
    
    clean_rows = []
    phone_cols = [c for c in df.columns if "Phone" in c and "Value" in c]

    for index, row in df.iterrows():
        # Optional: Skip if not in 'myContacts' (uncomment if you want this)
        # labels = str(row.get('Labels', ''))
        # if 'myContacts' not in labels: continue

        original_full_name = get_full_name(row)
        
        # Create search string
        row_str = " ".join([str(x) for x in row.values if str(x).lower() != 'nan'])
        
        # Identify creator AND get a cleaner version of the name
        final_name, creator = clean_name_and_find_creator(original_full_name, row_str)
        
        # Check if the RESULTING name is valid
        if is_garbage_name(final_name):
            continue

        # Extract Phones
        contact_phones = set()
        for col in phone_cols:
            raw_val = str(row.get(col, '') or '')
            if not raw_val or raw_val.lower() == 'nan': continue
            
            parts = raw_val.replace(' ::: ', ':::').split(':::')
            for part in parts:
                normalized = normalize_phone(part)
                if normalized:
                    contact_phones.add(normalized)

        if contact_phones:
            for phone in contact_phones:
                clean_rows.append({
                    "Name": final_name,
                    "Phone": phone,
                    "Created By": creator
                })

    output_df = pd.DataFrame(clean_rows)
    output_df.drop_duplicates(inplace=True)
    output_df.to_csv(OUTPUT_FILE, index=False, encoding='utf-8-sig')
    print(f"Success! Processed {len(df)} contacts.")
    print(f"Generated {len(output_df)} clean rows.")
    print(f"Done! Saved to {OUTPUT_FILE}")

if __name__ == "__main__":
    process_contacts()