import pandas as pd
import os

# Paths relative to the script location
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
INPUT_FILE = os.path.join(BASE_DIR, 'cleaned_contacts_final.csv')
OUTPUT_FILE = os.path.join(BASE_DIR, 'phone_conflicts.csv')

def find_conflicts():
    if not os.path.exists(INPUT_FILE):
        print(f"Error: {INPUT_FILE} not found. Please run clean_final.py first.")
        return

    print(f"Reading {INPUT_FILE}...")
    # Read the CSV
    try:
        df = pd.read_csv(INPUT_FILE)
    except Exception as e:
        print(f"Error reading CSV: {e}")
        return

    if df.empty:
        print("The input CSV is empty.")
        return

    # Add the CSV row number (1-indexed). 
    # Since header is line 1, the first data row is line 2.
    df['CSV Row'] = df.index + 2
    
    # Identify duplicate phone numbers.
    # Because clean_final.py already dropped duplicates of the (Name, Phone, Created By) triplet,
    # any duplicate phone numbers in this file MUST have a different Name or Created By value.
    conflicts = df[df.duplicated(subset=['Phone'], keep=False)].copy()
    
    if conflicts.empty:
        print("No phone number conflicts found (all phone numbers have unique Name/Creator combinations).")
        return

    # Sort by Phone and then Row to make it easy to compare
    conflicts = conflicts.sort_values(by=['Phone', 'CSV Row'])
    
    # Reorder columns to put CSV Row first or last as preferred (usually helpful at the start)
    cols = ['CSV Row', 'Name', 'Phone', 'Created By']
    conflicts = conflicts[cols]

    # Save to the new CSV
    try:
        conflicts.to_csv(OUTPUT_FILE, index=False, encoding='utf-8-sig')
        print(f"\nSuccess!")
        print(f"Found {len(conflicts)} conflicting rows across {conflicts['Phone'].nunique()} unique phone numbers.")
        print(f"Saved conflicts to: {OUTPUT_FILE}")
    except Exception as e:
        print(f"Error saving CSV: {e}")

if __name__ == "__main__":
    find_conflicts()
