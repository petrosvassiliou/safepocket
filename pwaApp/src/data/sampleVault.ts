import type { VaultItem } from '../types';
import { applyAutoBranding } from '../ui/institutionBrands';

export function createInitialSampleVaultItems(): VaultItem[] {
  const now = Date.now();
  // Set passport expiry ~48 days from current date to demonstrate automated 90-day expiry warning badge
  const soonDate = new Date(now + 48 * 24 * 60 * 60 * 1000)
    .toISOString()
    .slice(0, 10);

  const items: VaultItem[] = [
    {
      schemaVersion: 1,
      id: 'item-card-alpine-metal',
      category: 'PAYMENT_CARD',
      title: 'N26 Metal World Elite',
      subtitle: 'Primary Eurozone Travel & Daily Debit',
      favorite: true,
      pinned: true,
      tags: ['travel', 'sepa', 'metal', 'reise'],
      cardTheme: {
        paletteId: 'alpine_emerald',
        networkBrand: 'Mastercard'
      },
      fields: [
        {
          id: 'f-ch',
          labelKey: 'field_cardholder',
          value: 'CLARA MÜLLER',
          valueType: 'TEXT'
        },
        {
          id: 'f-cn',
          labelKey: 'field_card_number',
          value: '5355 8910 4421 7804',
          valueType: 'CARD_NUMBER',
          isSensitive: true
        },
        {
          id: 'f-exp',
          labelKey: 'field_expiry_date',
          value: '2029-08',
          valueType: 'DATE_ISO'
        },
        {
          id: 'f-cvv',
          labelKey: 'field_cvv',
          value: '849',
          valueType: 'SENSITIVE_SECRET',
          isSensitive: true
        },
        {
          id: 'f-pin',
          labelKey: 'field_pin',
          value: '2940',
          valueType: 'SENSITIVE_SECRET',
          isSensitive: true
        },
        {
          id: 'f-bank',
          labelKey: 'field_issuing_bank',
          value: 'N26 Bank SE (Berlin)',
          valueType: 'TEXT'
        },
        {
          id: 'f-iban',
          labelKey: 'field_iban',
          value: 'DE89 3704 0044 0532 0130 00',
          valueType: 'IBAN',
          isSensitive: true
        },
        {
          id: 'f-bic',
          labelKey: 'field_bic',
          value: 'NTSBDEB1XXX',
          valueType: 'TEXT'
        },
        {
          id: 'f-sup',
          labelKey: 'field_support_phone',
          value: '+49 30 364286880',
          valueType: 'PHONE'
        }
      ],
      attachments: [],
      createdAtEpochMs: now - 86_400_000 * 30,
      updatedAtEpochMs: now - 3_600_000 * 5
    },
    {
      schemaVersion: 1,
      id: 'item-doc-hellenic-passport',
      category: 'OFFICIAL_DOCUMENT',
      title: 'Ελληνικό Διαβατήριο / Hellenic Passport',
      subtitle: 'Biometric EU Travel Document • Αθήνα',
      favorite: true,
      tags: ['passport', 'eu', 'αθήνα', 'border'],
      documentSubtype: 'PASSPORT',
      cardTheme: {
        paletteId: 'aegean_cobalt'
      },
      fields: [
        {
          id: 'd-type',
          labelKey: 'field_document_subtype',
          value: 'Biometric Passport (ICAO eMRTD)',
          valueType: 'TEXT'
        },
        {
          id: 'd-num',
          labelKey: 'field_document_number',
          value: 'ΑΤ8492017',
          valueType: 'SENSITIVE_SECRET',
          isSensitive: true
        },
        {
          id: 'd-auth',
          labelKey: 'field_issuing_authority',
          value: 'Αρχηγείο Ελληνικής Αστυνομίας — Αθήνα',
          valueType: 'TEXT'
        },
        {
          id: 'd-iss',
          labelKey: 'field_issue_date',
          value: '2021-12-30',
          valueType: 'DATE_ISO'
        },
        {
          id: 'd-exp',
          labelKey: 'field_expiry_date',
          value: soonDate,
          valueType: 'DATE_ISO'
        },
        {
          id: 'd-nat',
          labelKey: 'field_nationality',
          value: 'Ελληνική / Hellenic (GR)',
          valueType: 'TEXT'
        }
      ],
      attachments: [
        {
          id: 'att-passport-front',
          fileName: 'hellenic_passport_biodata_page_600dpi.svg',
          mimeType: 'image/svg+xml',
          side: 'front',
          sizeBytes: 1_428_480,
          sha256Hex:
            '6c8f3e9b2d1a049587654321fedcba9876543210abcdef1234567890abcdef12',
          ivHex: 'a1b2c3d4e5f60718293a4b5c'
        }
      ],
      createdAtEpochMs: now - 86_400_000 * 60,
      updatedAtEpochMs: now - 3_600_000 * 12
    },
    {
      schemaVersion: 1,
      id: 'item-identity-primary',
      category: 'IDENTITY',
      title: 'Clara Sofia Müller',
      subtitle: 'Primary European Tax & Civil Identity',
      favorite: true,
      tags: ['tax', 'health', 'resident', 'münchen'],
      cardTheme: {
        paletteId: 'forest_sage',
        showVisualCard: true
      },
      fields: [
        {
          id: 'i-name',
          labelKey: 'field_full_name',
          value: 'Clara Sofia Müller',
          valueType: 'TEXT'
        },
        {
          id: 'i-dob',
          labelKey: 'field_birthdate',
          value: '1992-04-19',
          valueType: 'DATE_ISO'
        },
        {
          id: 'i-tax',
          labelKey: 'field_tax_id',
          value: '65 928 417 039',
          valueType: 'SENSITIVE_SECRET',
          isSensitive: true
        },
        {
          id: 'i-ssn',
          labelKey: 'field_ssn',
          value: '19 190492 M 018',
          valueType: 'SENSITIVE_SECRET',
          isSensitive: true
        },
        {
          id: 'i-hi',
          labelKey: 'field_health_insurance',
          value: 'A109482751 (TK)',
          valueType: 'SENSITIVE_SECRET',
          isSensitive: true
        },
        {
          id: 'i-addr',
          labelKey: 'field_address',
          value: 'Maximilianstraße 42, 80539 München, Deutschland',
          valueType: 'MULTILINE_ADDRESS'
        },
        {
          id: 'i-email',
          labelKey: 'field_email',
          value: 'clara.mueller@posteo.de',
          valueType: 'EMAIL'
        },
        {
          id: 'i-phone',
          labelKey: 'field_phone',
          value: '+49 171 8924510',
          valueType: 'PHONE'
        }
      ],
      attachments: [],
      createdAtEpochMs: now - 86_400_000 * 90,
      updatedAtEpochMs: now - 3_600_000 * 24
    },
    {
      schemaVersion: 1,
      id: 'item-doc-cie-italy',
      category: 'OFFICIAL_DOCUMENT',
      title: "Carta d'Identità Elettronica (CIE)",
      subtitle: 'Ministero dell’Interno • Milano',
      favorite: false,
      tags: ['cie', 'national-id', 'milano'],
      documentSubtype: 'NATIONAL_ID',
      cardTheme: {
        paletteId: 'terracotta_gold'
      },
      fields: [
        {
          id: 'c-sub',
          labelKey: 'field_document_subtype',
          value: 'National ID Card (CIE 3.0)',
          valueType: 'TEXT'
        },
        {
          id: 'c-num',
          labelKey: 'field_document_number',
          value: 'CA84920FL',
          valueType: 'SENSITIVE_SECRET',
          isSensitive: true
        },
        {
          id: 'c-auth',
          labelKey: 'field_issuing_authority',
          value: 'Comune di Milano',
          valueType: 'TEXT'
        },
        {
          id: 'c-iss',
          labelKey: 'field_issue_date',
          value: '2023-05-14',
          valueType: 'DATE_ISO'
        },
        {
          id: 'c-exp',
          labelKey: 'field_expiry_date',
          value: '2033-05-14',
          valueType: 'DATE_ISO'
        }
      ],
      attachments: [
        {
          id: 'att-cie-front',
          fileName: 'cie_front_scan.svg',
          mimeType: 'image/svg+xml',
          side: 'front',
          sizeBytes: 612_800,
          sha256Hex:
            '91a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f80',
          ivHex: '0102030405060708090a0b0c'
        },
        {
          id: 'att-cie-back',
          fileName: 'cie_back_mrz_scan.svg',
          mimeType: 'image/svg+xml',
          side: 'back',
          sizeBytes: 589_120,
          sha256Hex:
            '445566778899aabbccddeeff00112233445566778899aabbccddeeff00112233',
          ivHex: '0c0b0a090807060504030201'
        }
      ],
      createdAtEpochMs: now - 86_400_000 * 40,
      updatedAtEpochMs: now - 3_600_000 * 48
    },
    {
      schemaVersion: 1,
      id: 'item-note-emergency-kit',
      category: 'SECURE_NOTE',
      title: 'Household Safe & Notary Deeds Protocol',
      subtitle: 'Encrypted physical vault combinations & emergency instructions',
      favorite: false,
      tags: ['notary', 'safe', 'emergency', 'home'],
      fields: [
        {
          id: 'n-md',
          labelKey: 'field_markdown_note',
          value: `### Physical Wall Safe & Deed Registry
Keep these reference numbers accessible offline during travel or emergencies.

\`\`\`
Wall Safe Combination: 48-19-72-06
Notary Deed Dossier: URK-2025-8841-MUC
Home Insurance Policy: ALLIANZ-DE-9940218
\`\`\`

- **Embassy Consular Hotline:** +30 210 728 4000
- **Hardware Security Key Backup Location:** Bank Schließfach #214`,
          valueType: 'MARKDOWN',
          isSensitive: true
        }
      ],
      attachments: [],
      createdAtEpochMs: now - 86_400_000 * 15,
      updatedAtEpochMs: now - 3_600_000 * 2
    },
    {
      schemaVersion: 1,
      id: 'item-bank-ing-giro',
      category: 'BANK_ACCOUNT',
      title: 'ING Girokonto (Gehalt & Alltag)',
      subtitle: 'ING-DiBa AG • Frankfurt am Main',
      favorite: true,
      tags: ['bank', 'girokonto', 'sepa', 'gehalt'],
      cardTheme: {
        paletteId: 'sunset_coral',
        networkBrand: 'SEPA IBAN'
      },
      fields: [
        {
          id: 'ba-holder',
          labelKey: 'field_account_holder',
          value: 'CLARA SOFIA MÜLLER',
          valueType: 'TEXT'
        },
        {
          id: 'ba-iban',
          labelKey: 'field_iban',
          value: 'DE89 3704 0044 0532 0130 00',
          valueType: 'IBAN',
          isSensitive: true
        },
        {
          id: 'ba-bic',
          labelKey: 'field_bic',
          value: 'INGDDEFFXXX',
          valueType: 'TEXT'
        },
        {
          id: 'ba-acct',
          labelKey: 'field_account_number',
          value: '0532013000',
          valueType: 'SENSITIVE_SECRET',
          isSensitive: true
        },
        {
          id: 'ba-blz',
          labelKey: 'field_routing_code',
          value: '37040044',
          valueType: 'TEXT'
        },
        {
          id: 'ba-type',
          labelKey: 'field_account_type',
          value: 'Girokonto (Einzelkonto)',
          valueType: 'TEXT'
        },
        {
          id: 'ba-bank',
          labelKey: 'field_issuing_bank',
          value: 'ING-DiBa AG',
          valueType: 'TEXT'
        },
        {
          id: 'ba-phone',
          labelKey: 'field_support_phone',
          value: '+49 69 34 22 24',
          valueType: 'PHONE'
        }
      ],
      attachments: [],
      createdAtEpochMs: now - 86_400_000 * 28,
      updatedAtEpochMs: now - 3_600_000 * 8
    },
    {
      schemaVersion: 1,
      id: 'item-ins-tk-egk',
      category: 'INSURANCE_CARD',
      title: 'Techniker Krankenkasse — Gesundheitskarte (eGK)',
      subtitle: 'Gesetzliche Krankenversicherung • EHIC Europa',
      favorite: true,
      tags: ['krankenkasse', 'egk', 'ehic', 'gesundheit', 'versicherung'],
      cardTheme: {
        paletteId: 'arctic_cyan',
        networkBrand: 'eGK G2.1'
      },
      fields: [
        {
          id: 'ins-holder',
          labelKey: 'field_full_name',
          value: 'Clara Sofia Müller',
          valueType: 'TEXT'
        },
        {
          id: 'ins-prov',
          labelKey: 'field_insurer_name',
          value: 'Techniker Krankenkasse (TK)',
          valueType: 'TEXT'
        },
        {
          id: 'ins-num',
          labelKey: 'field_insurance_number',
          value: 'A109482751',
          valueType: 'SENSITIVE_SECRET',
          isSensitive: true
        },
        {
          id: 'ins-ik',
          labelKey: 'field_insurer_id',
          value: '101575519',
          valueType: 'TEXT'
        },
        {
          id: 'ins-plan',
          labelKey: 'field_coverage_plan',
          value: 'GKV Vollversicherung + Auslands-EHIC',
          valueType: 'TEXT'
        },
        {
          id: 'ins-exp',
          labelKey: 'field_expiry_date',
          value: '2029-12-31',
          valueType: 'DATE_ISO'
        },
        {
          id: 'ins-phone',
          labelKey: 'field_support_phone',
          value: '+49 800 285 85 85',
          valueType: 'PHONE'
        }
      ],
      attachments: [],
      createdAtEpochMs: now - 86_400_000 * 22,
      updatedAtEpochMs: now - 3_600_000 * 6
    },
    {
      schemaVersion: 1,
      id: 'item-mem-adac-plus',
      category: 'MEMBERSHIP_CARD',
      title: 'ADAC Plus-Mitgliedschaft',
      subtitle: 'Europaweiter Pannenschutz & Krankenrücktransport',
      favorite: false,
      tags: ['mitgliedschaft', 'adac', 'mobilitaet', 'club'],
      cardTheme: {
        paletteId: 'pearl_ivory',
        barcodeFormat: 'CODE_128',
        networkBrand: 'PLUS MEMBER'
      },
      fields: [
        {
          id: 'mem-name',
          labelKey: 'field_member_name',
          value: 'CLARA SOFIA MÜLLER',
          valueType: 'TEXT'
        },
        {
          id: 'mem-org',
          labelKey: 'field_organization',
          value: 'ADAC e.V. München',
          valueType: 'TEXT'
        },
        {
          id: 'mem-id',
          labelKey: 'field_membership_id',
          value: '7081 4920 1184',
          valueType: 'TEXT'
        },
        {
          id: 'mem-tier',
          labelKey: 'field_membership_tier',
          value: 'ADAC Plus (Europa & Welt)',
          valueType: 'TEXT'
        },
        {
          id: 'mem-exp',
          labelKey: 'field_expiry_date',
          value: '2027-09-30',
          valueType: 'DATE_ISO'
        },
        {
          id: 'mem-phone',
          labelKey: 'field_support_phone',
          value: '+49 89 22 22 22',
          valueType: 'PHONE'
        }
      ],
      attachments: [],
      createdAtEpochMs: now - 86_400_000 * 18,
      updatedAtEpochMs: now - 3_600_000 * 4
    },
    {
      schemaVersion: 1,
      id: 'item-card-db-visa',
      category: 'PAYMENT_CARD',
      title: 'Deutsche Bank Card Plus VISA',
      subtitle: 'Kreditkarte • Weltweit & Kontaktlos',
      favorite: true,
      pinned: true,
      tags: ['kreditkarte', 'visa', 'deutsche-bank', 'reise'],
      cardTheme: {
        paletteId: 'custom',
        customColorHex: '#0018A8',
        networkBrand: 'VISA',
        institutionId: 'deutsche_bank',
        institutionName: 'Deutsche Bank',
        institutionDomain: 'deutsche-bank.de',
        autoBrandColor: true
      },
      fields: [
        {
          id: 'db-ch',
          labelKey: 'field_cardholder',
          value: 'CLARA SOFIA MÜLLER',
          valueType: 'TEXT'
        },
        {
          id: 'db-pan',
          labelKey: 'field_card_number',
          value: '4532 0151 1283 0366',
          valueType: 'CARD_NUMBER',
          isSensitive: true
        },
        {
          id: 'db-net',
          labelKey: 'field_card_network',
          value: 'VISA',
          valueType: 'TEXT'
        },
        {
          id: 'db-type',
          labelKey: 'field_card_type',
          value: 'Credit Card (Gold)',
          valueType: 'TEXT'
        },
        {
          id: 'db-from',
          labelKey: 'field_valid_from',
          value: '2025-11',
          valueType: 'DATE_ISO'
        },
        {
          id: 'db-exp',
          labelKey: 'field_expiry_date',
          value: '2029-11',
          valueType: 'DATE_ISO'
        },
        {
          id: 'db-cvv',
          labelKey: 'field_cvv',
          value: '742',
          valueType: 'SENSITIVE_SECRET',
          isSensitive: true
        },
        {
          id: 'db-pin',
          labelKey: 'field_pin',
          value: '4819',
          valueType: 'SENSITIVE_SECRET',
          isSensitive: true
        },
        {
          id: 'db-bank',
          labelKey: 'field_issuing_bank',
          value: 'Deutsche Bank AG',
          valueType: 'TEXT'
        },
        {
          id: 'db-dom',
          labelKey: 'field_institution_domain',
          value: 'https://www.deutsche-bank.de',
          valueType: 'TEXT'
        },
        {
          id: 'db-limit',
          labelKey: 'field_credit_limit',
          value: '7.500 EUR',
          valueType: 'TEXT'
        },
        {
          id: 'db-iban',
          labelKey: 'field_iban',
          value: 'DE12 5007 0010 0928 4410 00',
          valueType: 'IBAN',
          isSensitive: true
        },
        {
          id: 'db-bic',
          labelKey: 'field_bic',
          value: 'DEUTDEFFXXX',
          valueType: 'TEXT'
        },
        {
          id: 'db-addr',
          labelKey: 'field_billing_address',
          value: 'Maximilianstraße 42, 80539 München, Deutschland',
          valueType: 'MULTILINE_ADDRESS'
        },
        {
          id: 'db-3ds',
          labelKey: 'field_online_passcode',
          value: 'PhotoTAN / BestSign Aktiviert',
          valueType: 'SENSITIVE_SECRET',
          isSensitive: true
        },
        {
          id: 'db-emg',
          labelKey: 'field_emergency_phone',
          value: '+49 116 116',
          valueType: 'PHONE'
        },
        {
          id: 'db-sup',
          labelKey: 'field_support_phone',
          value: '+49 69 910-10000',
          valueType: 'PHONE'
        }
      ],
      attachments: [],
      createdAtEpochMs: now - 86_400_000 * 10,
      updatedAtEpochMs: now - 3_600_000 * 1
    }
  ];

  return items.map((item) => ({
    ...applyAutoBranding(item),
    isSampleItem: true
  }));
}

export function isBuiltInSampleItem(
  item: Pick<VaultItem, 'id' | 'isSampleItem'>,
  knownSampleIds?: ReadonlySet<string>
): boolean {
  if (item.isSampleItem) return true;
  if (knownSampleIds) return knownSampleIds.has(item.id);
  return (
    item.id === 'item-card-db-visa' ||
    item.id === 'item-card-n26-metal' ||
    item.id === 'item-card-alpine-metal' ||
    item.id === 'item-bank-ing-giro' ||
    item.id === 'item-bank-ing-sepa' ||
    item.id === 'item-bank-sepa-main' ||
    item.id === 'item-ins-tk-egk' ||
    item.id === 'item-ins-ehic-eu' ||
    item.id === 'item-mem-bahncard' ||
    item.id === 'item-mem-adac-plus' ||
    item.id === 'item-identity-primary' ||
    item.id === 'item-id-clara' ||
    item.id === 'item-id-eu-citizen' ||
    item.id === 'item-doc-hellenic-passport' ||
    item.id === 'item-doc-cie-italy' ||
    item.id === 'item-doc-passport-de' ||
    item.id === 'item-doc-passport-eu' ||
    item.id === 'item-note-home-safe' ||
    item.id === 'item-note-emergency' ||
    item.id === 'item-note-emergency-kit' ||
    item.id === 'item-note-vault-codes'
  );
}

export function getPrimaryCopyableField(
  item: VaultItem
): VaultItem['fields'][number] | null {
  const priorityByCategory: Record<VaultItem['category'], string[]> = {
    PAYMENT_CARD: ['field_card_number', 'field_iban', 'field_cvv'],
    BANK_ACCOUNT: ['field_iban', 'field_account_number', 'field_bic'],
    INSURANCE_CARD: ['field_insurance_number', 'field_insurer_id'],
    MEMBERSHIP_CARD: ['field_membership_id', 'field_barcode_value'],
    IDENTITY: ['field_tax_id', 'field_ssn', 'field_email', 'field_phone'],
    OFFICIAL_DOCUMENT: ['field_document_number', 'field_pin'],
    SECURE_NOTE: ['field_markdown_note', 'field_pin', 'field_document_number']
  };
  const keys = priorityByCategory[item.category] ?? [];
  for (const key of keys) {
    const found = item.fields.find(
      (f) => !f.isCustomLabel && f.labelKey === key && f.value.trim().length > 0
    );
    if (found) return found;
  }
  return item.fields.find((f) => f.value.trim().length > 0) ?? null;
}

export function buildSampleSvgScanBytes(params: {
  title: string;
  docNumber: string;
  authority: string;
  holder: string;
  side: 'front' | 'back';
}): Uint8Array {
  const esc = (s: string) =>
    s
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;');
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 640 400" width="640" height="400">
    <defs>
      <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0%" stop-color="${params.side === 'front' ? '#12293d' : '#1b2631'}" />
        <stop offset="100%" stop-color="#0b1722" />
      </linearGradient>
    </defs>
    <rect width="640" height="400" rx="22" fill="url(#bg)" stroke="#38b296" stroke-width="2"/>
    <text x="32" y="46" fill="#8fd8c6" font-family="monospace" font-size="13" letter-spacing="2">SAFEPOCKET AES-256-GCM DECRYPTED RAM SCAN • ${params.side.toUpperCase()}</text>
    <text x="32" y="84" fill="#f8fafc" font-family="sans-serif" font-size="22" font-weight="bold">${esc(params.title)}</text>
    <text x="32" y="114" fill="#94a3b8" font-family="sans-serif" font-size="14">${esc(params.authority)}</text>
    <rect x="32" y="142" width="128" height="156" rx="12" fill="#1e3a52" stroke="#475569"/>
    <circle cx="96" cy="198" r="28" fill="#64748b"/>
    <path d="M56 278 C56 244, 136 244, 136 278" fill="#64748b"/>
    <text x="188" y="172" fill="#94a3b8" font-family="monospace" font-size="12">HOLDER</text>
    <text x="188" y="196" fill="#f8fafc" font-family="monospace" font-size="18" font-weight="bold">${esc(params.holder)}</text>
    <text x="188" y="236" fill="#94a3b8" font-family="monospace" font-size="12">DOCUMENT NUMBER</text>
    <text x="188" y="262" fill="#5eead4" font-family="monospace" font-size="20" font-weight="bold">${esc(params.docNumber)}</text>
    <rect x="24" y="324" width="592" height="54" rx="8" fill="#071018"/>
    <text x="38" y="347" fill="#cbd5e1" font-family="monospace" font-size="13" letter-spacing="2">P&lt;GRCMUELLER&lt;&lt;CLARA&lt;SOFIA&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;</text>
    <text x="38" y="368" fill="#cbd5e1" font-family="monospace" font-size="13" letter-spacing="2">${esc(params.docNumber)}&lt;4GRC9204198F3012306&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;&lt;04</text>
  </svg>`;
  return new TextEncoder().encode(svg);
}

