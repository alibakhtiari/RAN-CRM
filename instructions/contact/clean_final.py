import pandas as pd
import phonenumbers
import re
import os
import glob
import quopri

# Use the same 'csvs' folder but look for .vcf files now
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

def decode_quoted_printable(text, charset='utf-8'):
    """Decodes quoted-printable encoded text into the specified charset."""
    if not text:
        return ""
    try:
        # Some VCFs use =0D=0A for newlines, we clean them up
        text = text.replace('=\n', '').replace('=\r\n', '')
        decoded_bytes = quopri.decodestring(text.encode('ascii'))
        return decoded_bytes.decode(charset, errors='replace')
    except Exception:
        return text

def parse_vcf(file_path):
    """Parses a VCF file and returns a list of contact dictionaries."""
    contacts = []
    current_contact = None
    
    try:
        with open(file_path, 'r', encoding='utf-8', errors='replace') as f:
            lines = f.readlines()
    except Exception as e:
        print(f"Error opening {file_path}: {e}")
        return []

    # Handle line folding
    folded_lines = []
    for line in lines:
        if line.startswith(' ') or line.startswith('\t'):
            if folded_lines:
                folded_lines[-1] = folded_lines[-1].rstrip() + line.lstrip()
        else:
            folded_lines.append(line.strip())

    for line in folded_lines:
        if not line: continue
        
        if line == "BEGIN:VCARD":
            current_contact = {'TEL': []}
            continue
        elif line == "END:VCARD":
            if current_contact:
                contacts.append(current_contact)
            current_contact = None
            continue
            
        if not current_contact or ':' not in line:
            continue
            
        key_part, value = line.split(':', 1)
        key_parts = key_part.split(';')
        tag = key_parts[0].upper()
        params = key_parts[1:]
        
        # Check for Quoted-Printable and Charset
        is_qp = any('ENCODING=QUOTED-PRINTABLE' in p.upper() for p in params)
        charset = 'utf-8'
        for p in params:
            if p.upper().startswith('CHARSET='):
                charset = p.split('=')[1].lower()
        
        if is_qp:
            value = decode_quoted_printable(value, charset)
            
        if tag == 'FN':
            current_contact['Full Name'] = value
        elif tag == 'N':
            parts = value.split(';')
            current_contact['Surname'] = parts[0] if len(parts) > 0 else ""
            current_contact['First Name'] = parts[1] if len(parts) > 1 else ""
        elif tag == 'TEL':
            current_contact['TEL'].append(value)
        elif tag == 'ORG':
            current_contact['Organization'] = value
        elif tag == 'EMAIL':
            current_contact['Email'] = value
            
    return contacts

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
    # Find all VCF files in the folder
    vcf_files = glob.glob(os.path.join(INPUT_DIR, "*.vcf"))
    
    if not vcf_files:
        print(f"No VCF files found in {INPUT_DIR}")
        return

    print(f"Found {len(vcf_files)} VCF files. Processing...")
    
    clean_rows = []
    total_raw_contacts = 0

    for f_path in vcf_files:
        print(f"Reading {os.path.basename(f_path)}...")
        contacts = parse_vcf(f_path)
        total_raw_contacts += len(contacts)
        
        for c in contacts:
            # 1. Get Name
            first = c.get('First Name', '').strip()
            last = c.get('Surname', '').strip()
            full = c.get('Full Name', '').strip()
            
            # Use Full Name if First/Last are empty
            name_to_clean = f"{first} {last}".strip()
            if not name_to_clean:
                name_to_clean = full
            
            name_to_clean = normalize_persian_chars(name_to_clean)
            
            # 2. Create Search String for Creator Identification
            # Combine all text fields to find keywords
            search_vals = [str(v) for v in c.values() if not isinstance(v, list)]
            search_vals.extend(c.get('TEL', []))
            row_str = " ".join(search_vals)
            
            # 3. Clean Name and Identify Creator
            final_name, creator = clean_name_and_find_creator(name_to_clean, row_str)
            
            # 4. Filter Garbage
            if is_garbage_name(final_name):
                continue

            # 5. Extract and Normalize Phones
            contact_phones = set()
            for raw_phone in c.get('TEL', []):
                if not raw_phone: continue
                # Handle potential ::: separator if it exists in VCF (rare but possible if exported from certain tools)
                parts = raw_phone.replace(' ::: ', ':::').split(':::')
                for part in parts:
                    normalized = normalize_phone(part)
                    if normalized:
                        contact_phones.add(normalized)

            # 6. Add to result
            if contact_phones:
                for phone in contact_phones:
                    clean_rows.append({
                        "Name": final_name,
                        "Phone": phone,
                        "Created By": creator
                    })

    if not clean_rows:
        print("No valid contacts found after processing.")
        return

    output_df = pd.DataFrame(clean_rows)
    output_df.drop_duplicates(inplace=True)
    output_df.to_csv(OUTPUT_FILE, index=False, encoding='utf-8-sig')
    
    print(f"\nSuccess!")
    print(f"Total VCF contacts read: {total_raw_contacts}")
    print(f"Generated {len(output_df)} clean rows.")
    print(f"Done! Saved to {OUTPUT_FILE}")

if __name__ == "__main__":
    process_contacts()