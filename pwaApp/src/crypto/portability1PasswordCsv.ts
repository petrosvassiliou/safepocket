import {
  APP_BUILD_INFO,
  type FieldValueType,
  SENSITIVE_FIELD_KEYS,
  type VaultCategory,
  VAULT_CATEGORIES,
  type VaultField,
  type VaultItem
} from '../types';
import {
  applyAutoBranding,
  cleanInstitutionDomain,
  resolveDualBrand
} from '../ui/institutionBrands';

export interface VaultImportReport {
  importedItems: VaultItem[];
  skippedLoginCount: number;
  sourceFormat: string;
}

const LOGIN_CATEGORY_UUIDS = new Set(['001', '100', '101', '102', '111', '112']);

/**
 * Imports a 1Password `.1pux` archive (ZIP containing `export.data` JSON) or unwrapped `export.data` JSON.
 * Supports both PKZIP STORED (method 0) and DEFLATE (method 8 via Web `DecompressionStream('deflate-raw')`)
 * as well as Central Directory size lookup for streamed ZIP entries.
 */
export async function importFrom1Password1Pux(
  rawBytes: Uint8Array,
  includeLoginsAsNotes = false,
  nowEpochMs: number = Date.now()
): Promise<VaultImportReport> {
  let exportDataJson: string;

  if (
    rawBytes.byteLength >= 4 &&
    rawBytes[0] === 0x50 &&
    rawBytes[1] === 0x4b
  ) {
    const entries = await extractZipEntriesAsync(rawBytes);
    let foundBytes: Uint8Array | undefined;
    for (const [entryName, data] of entries.entries()) {
      if (entryName === 'export.data' || entryName.endsWith('/export.data')) {
        foundBytes = data;
        break;
      }
    }
    if (!foundBytes) {
      throw new Error('Invalid .1pux archive: export.data entry not found');
    }
    exportDataJson = new TextDecoder().decode(foundBytes);
  } else {
    exportDataJson = new TextDecoder().decode(rawBytes);
  }

  return parse1PasswordExportDataJson(
    exportDataJson,
    includeLoginsAsNotes,
    nowEpochMs
  );
}

export function parse1PasswordExportDataJson(
  rawJson: string,
  includeLoginsAsNotes = false,
  nowEpochMs: number = Date.now()
): VaultImportReport {
  const root = JSON.parse(rawJson) as {
    accounts?: Array<{
      vaults?: Array<{
        items?: Array<Record<string, unknown>>;
      }>;
    }>;
  };

  const accounts = Array.isArray(root.accounts) ? root.accounts : [];
  const imported: VaultItem[] = [];
  let skippedLoginCount = 0;
  let counter = 0;

  for (const acc of accounts) {
    const vaults = Array.isArray(acc.vaults) ? acc.vaults : [];
    for (const vault of vaults) {
      const items = Array.isArray(vault.items) ? vault.items : [];
      for (const itemObj of items) {
        const state = String(itemObj.state ?? 'active').toLowerCase();
        if (state === 'trashed') continue;

        const catUuid = String(itemObj.categoryUuid ?? '003');
        const isLogin = LOGIN_CATEGORY_UUIDS.has(catUuid);
        if (isLogin && !includeLoginsAsNotes) {
          skippedLoginCount++;
          continue;
        }

        const uuid = String(
          itemObj.uuid || `item-1p-${nowEpochMs}-${counter++}`
        );
        const overview = (itemObj.overview ?? {}) as Record<string, unknown>;
        const details = (itemObj.details ?? {}) as Record<string, unknown>;

        const title =
          String(overview.title ?? '').trim() || 'Imported 1Password Item';
        const subtitle = String(overview.ainfo ?? overview.ps ?? '').trim();
        const url = String(overview.url ?? '').trim();
        const tags = Array.isArray(overview.tags)
          ? overview.tags.map((t) => String(t).trim()).filter(Boolean)
          : [];
        const favorite = Number(itemObj.favIndex ?? 0) > 0;

        const rawCreated = Number(itemObj.createdAt ?? 0);
        const rawUpdated = Number(itemObj.updatedAt ?? 0);
        const createdAtEpochMs =
          rawCreated > 0
            ? rawCreated < 100_000_000_000
              ? rawCreated * 1000
              : rawCreated
            : nowEpochMs;
        const updatedAtEpochMs =
          rawUpdated > 0
            ? rawUpdated < 100_000_000_000
              ? rawUpdated * 1000
              : rawUpdated
            : createdAtEpochMs;

        const targetCategory = map1PasswordCategoryUuid(catUuid);
        const rawAttrs: Array<{ id: string; title: string; value: string }> = [];

        if (Array.isArray(details.loginFields)) {
          for (const lf of details.loginFields as Array<Record<string, unknown>>) {
            const desig = String(lf.designation ?? lf.name ?? '').trim();
            const v = String(lf.value ?? '').trim();
            if (v) rawAttrs.push({ id: desig, title: desig, value: v });
          }
        }

        if (Array.isArray(details.sections)) {
          for (const sec of details.sections as Array<Record<string, unknown>>) {
            if (!Array.isArray(sec.fields)) continue;
            for (const fld of sec.fields as Array<Record<string, unknown>>) {
              const fid = String(fld.id ?? '').trim();
              const fTitle = String(fld.title ?? '').trim();
              const extracted = extract1PasswordFieldValue(
                fld.value as Record<string, unknown> | undefined
              );
              if (extracted) {
                rawAttrs.push({ id: fid, title: fTitle, value: extracted });
              }
            }
          }
        }

        const notesPlain = String(details.notesPlain ?? '').trim();
        const topPassword = String(details.password ?? '').trim();

        const fields = map1PasswordFieldsToSafePocket({
          category: targetCategory,
          rawAttrs,
          notesPlain,
          url,
          topPassword,
          isLoginAsNote: isLogin
        });

        const baseItem: VaultItem = {
          schemaVersion: 1,
          id: uuid.startsWith('item-') || uuid.startsWith('1p-') ? uuid : `1p-${uuid}`,
          category: targetCategory,
          title,
          subtitle,
          favorite,
          tags,
          fields,
          attachments: [],
          createdAtEpochMs,
          updatedAtEpochMs
        };

        imported.push(applyAutoBranding(baseItem));
      }
    }
  }

  return {
    importedItems: imported,
    skippedLoginCount,
    sourceFormat: '1Password (.1pux)'
  };
}

/**
 * Exports SafePocket vault items into a standard 1Password `.1pux` ZIP archive
 * containing `export.attributes` and `export.data`.
 */
export function exportTo1Password1Pux(
  items: ReadonlyArray<VaultItem>,
  exportedAtEpochSeconds: number = Math.floor(Date.now() / 1000)
): Uint8Array {
  const attributesJson = JSON.stringify({
    version: 3,
    description: `SafePocket v${APP_BUILD_INFO.versionName} 1Password Export`,
    createdAt: exportedAtEpochSeconds
  });

  const exportDataObj = {
    accounts: [
      {
        attrs: {
          accountName: 'SafePocket Vault',
          name: 'SafePocket User',
          domain: 'safepocket.local'
        },
        vaults: [
          {
            attrs: {
              uuid: 'safepocket-primary-vault',
              name: 'SafePocket',
              type: 'P'
            },
            items: items.map((item) =>
              serializeItemTo1PasswordObject(item, exportedAtEpochSeconds)
            )
          }
        ]
      }
    ]
  };

  const exportDataJson = JSON.stringify(exportDataObj, null, 2);
  const enc = new TextEncoder();
  return createStoredZipArchive(
    new Map<string, Uint8Array>([
      ['export.attributes', enc.encode(attributesJson)],
      ['export.data', enc.encode(exportDataJson)]
    ])
  );
}

/**
 * Imports items from 1Password CSV, SafePocket CSV, or Generic CSV.
 * Skips website Logins by default unless `includeLoginsAsNotes` is enabled.
 */
export function importFromCsv(
  rawCsv: string,
  includeLoginsAsNotes = false,
  nowEpochMs: number = Date.now()
): VaultImportReport {
  const cleaned = rawCsv.replace(/^\uFEFF/, '').trim();
  if (!cleaned) {
    return { importedItems: [], skippedLoginCount: 0, sourceFormat: 'CSV' };
  }

  const rows = parseRfc4180Csv(cleaned);
  if (rows.length < 2) {
    return { importedItems: [], skippedLoginCount: 0, sourceFormat: 'CSV' };
  }

  const headers = rows[0].map((h) => h.trim());
  const lowerHeaders = headers.map((h) => h.toLowerCase());
  const isSafePocketCsv =
    lowerHeaders.includes('category') &&
    (lowerHeaders.includes('fields_json') ||
      lowerHeaders.includes('fields_summary'));

  const imported: VaultItem[] = [];
  let skippedLoginCount = 0;

  for (let rIdx = 1; rIdx < rows.length; rIdx++) {
    const row = rows[rIdx];
    if (row.every((c) => !c.trim())) continue;

    const map = new Map<string, string>();
    for (let cIdx = 0; cIdx < headers.length; cIdx++) {
      const cell = (row[cIdx] ?? '').trim();
      if (cell) {
        map.set(headers[cIdx], cell);
      }
    }
    if (map.size === 0) continue;

    if (isSafePocketCsv) {
      const spItem = parseSafePocketCsvRow(map, nowEpochMs + rIdx);
      if (spItem) imported.push(applyAutoBranding(spItem));
      continue;
    }

    const rawType = getByAliases(
      map,
      'type',
      'category',
      'kategorie',
      'item type'
    ).toLowerCase();
    const isLogin =
      rawType === 'login' ||
      rawType === 'password' ||
      rawType === 'passwort' ||
      rawType === 'server';
    if (isLogin && !includeLoginsAsNotes) {
      skippedLoginCount++;
      continue;
    }

    const category = inferCategoryFromCsvRow(rawType, map);
    const title =
      getByAliases(map, 'title', 'name', 'bezeichnung', 'titel') ||
      getByAliases(map, 'bank', 'issuing bank', 'organization', 'insurer') ||
      `Imported Item #${rIdx}`;
    const subtitle = getByAliases(
      map,
      'subtitle',
      'untertitel',
      'account type',
      'type'
    );
    const tags = getByAliases(map, 'tags', 'tag', 'schlagworte')
      .split(/[,|;]/)
      .map((s) => s.trim())
      .filter(Boolean);
    const favRaw = getByAliases(map, 'favorite', 'fav', 'favorit').toLowerCase();
    const favorite = ['true', '1', 'yes', 'ja'].includes(favRaw);

    const fields = buildFieldsFromCsvRow(category, map, isLogin);
    const baseItem: VaultItem = {
      schemaVersion: 1,
      id: `csv-${nowEpochMs}-${rIdx}`,
      category,
      title,
      subtitle,
      favorite,
      tags,
      fields,
      attachments: [],
      createdAtEpochMs: nowEpochMs,
      updatedAtEpochMs: nowEpochMs
    };
    imported.push(applyAutoBranding(baseItem));
  }

  return {
    importedItems: imported,
    skippedLoginCount,
    sourceFormat: isSafePocketCsv ? 'SafePocket CSV' : '1Password / CSV'
  };
}

export function exportToSafePocketCsv(items: ReadonlyArray<VaultItem>): string {
  const header = [
    'id',
    'category',
    'title',
    'subtitle',
    'favorite',
    'pinned',
    'tags',
    'institution',
    'network_or_type',
    'card_color_hex',
    'fields_json'
  ].join(',');

  const lines = items.map((item) => {
    const resolved = resolveDualBrand(item);
    const fieldsJson = JSON.stringify(item.fields);
    return [
      escCsv(item.id),
      escCsv(item.category),
      escCsv(item.title),
      escCsv(item.subtitle),
      String(Boolean(item.favorite)),
      String(Boolean(item.pinned)),
      escCsv(item.tags.join('|')),
      escCsv(
        item.cardTheme?.institutionName ??
          resolved.institution?.displayName ??
          ''
      ),
      escCsv(item.cardTheme?.networkBrand ?? resolved.networkDisplayLabel),
      escCsv(
        item.cardTheme?.customColorHex ?? resolved.effectiveColorHex ?? ''
      ),
      escCsv(fieldsJson)
    ].join(',');
  });

  return [header, ...lines].join('\n');
}

export function exportTo1PasswordCsv(items: ReadonlyArray<VaultItem>): string {
  const columns = [
    'Title',
    'Type',
    'Cardholder Name',
    'Number',
    'Brand',
    'Expiry Date',
    'Verification Number',
    'PIN',
    'Issuing Bank',
    'IBAN',
    'SWIFT',
    'Account Number',
    'Routing Number',
    'Full Name',
    'Birthdate',
    'Document Number',
    'Organization',
    'Membership ID',
    'Website',
    'Notes',
    'Tags'
  ];

  const rows = [columns.join(',')];
  for (const item of items) {
    const typeLabel =
      item.category === 'PAYMENT_CARD'
        ? 'Credit Card'
        : item.category === 'BANK_ACCOUNT'
          ? 'Bank Account'
          : item.category === 'INSURANCE_CARD'
            ? 'Medical Record'
            : item.category === 'MEMBERSHIP_CARD'
              ? 'Membership'
              : item.category === 'IDENTITY'
                ? 'Identity'
                : item.category === 'OFFICIAL_DOCUMENT'
                  ? 'Passport'
                  : 'Secure Note';

    const fv = (key: string) =>
      item.fields.find((f) => !f.isCustomLabel && f.labelKey === key)?.value ??
      '';

    const notesCombined = [fv('field_markdown_note'), fv('field_notes')]
      .filter((s) => s.trim().length > 0)
      .join('\n\n');

    const values = [
      item.title,
      typeLabel,
      fv('field_cardholder') || fv('field_account_holder'),
      fv('field_card_number'),
      fv('field_card_network') || item.cardTheme?.networkBrand || '',
      fv('field_expiry_date'),
      fv('field_cvv'),
      fv('field_pin'),
      fv('field_issuing_bank') || fv('field_insurer_name'),
      fv('field_iban'),
      fv('field_bic'),
      fv('field_account_number') || fv('field_insurance_number'),
      fv('field_routing_code') || fv('field_insurer_id'),
      fv('field_full_name') || fv('field_member_name'),
      fv('field_birthdate'),
      fv('field_document_number'),
      fv('field_organization'),
      fv('field_membership_id'),
      fv('field_institution_domain') || item.cardTheme?.institutionDomain || '',
      notesCombined,
      item.tags.join(',')
    ];

    rows.push(values.map(escCsv).join(','));
  }

  return rows.join('\n');
}

// ============================================================================
// INTERNAL MAPPING HELPERS
// ============================================================================

function map1PasswordCategoryUuid(catUuid: string): VaultCategory {
  switch (catUuid) {
    case '002':
      return 'PAYMENT_CARD';
    case '104':
      return 'BANK_ACCOUNT';
    case '108':
      return 'INSURANCE_CARD';
    case '105':
    case '107':
      return 'MEMBERSHIP_CARD';
    case '004':
      return 'IDENTITY';
    case '103':
    case '106':
    case '109':
    case '110':
      return 'OFFICIAL_DOCUMENT';
    default:
      return 'SECURE_NOTE';
  }
}

function extract1PasswordFieldValue(
  valObj: Record<string, unknown> | undefined
): string {
  if (!valObj || typeof valObj !== 'object') return '';
  for (const [k, v] of Object.entries(valObj)) {
    if (v === null || v === undefined) continue;
    if (typeof v === 'string' || typeof v === 'number') {
      const c = String(v).trim();
      if (k === 'monthYear' && /^\d{6}$/.test(c)) {
        return `${c.slice(0, 4)}-${c.slice(4, 6)}`;
      }
      if (c) return c;
    } else if (typeof v === 'object') {
      const addrObj = v as Record<string, unknown>;
      const street = String(addrObj.street ?? '').trim();
      const zip = String(addrObj.zip ?? '').trim();
      const city = String(addrObj.city ?? '').trim();
      const country = String(addrObj.country ?? '').trim();
      const addr = [street, `${zip} ${city}`.trim(), country]
        .filter(Boolean)
        .join(', ');
      if (addr) return addr;
    }
  }
  return '';
}

function map1PasswordFieldsToSafePocket(params: {
  category: VaultCategory;
  rawAttrs: Array<{ id: string; title: string; value: string }>;
  notesPlain: string;
  url: string;
  topPassword: string;
  isLoginAsNote: boolean;
}): VaultField[] {
  const { category, rawAttrs, notesPlain, url, topPassword, isLoginAsNote } =
    params;
  const out: VaultField[] = [];
  let idx = 0;

  const addStd = (
    key: string,
    value: string,
    valueType: FieldValueType = 'TEXT'
  ) => {
    const trimmed = value.trim();
    if (!trimmed) return;
    if (out.some((f) => !f.isCustomLabel && f.labelKey === key)) return;
    out.push({
      id: `f-1p-${idx++}`,
      labelKey: key,
      value: trimmed,
      valueType,
      isSensitive:
        valueType === 'SENSITIVE_SECRET' || SENSITIVE_FIELD_KEYS.has(key)
    });
  };

  if (isLoginAsNote) {
    const lines: string[] = [];
    if (url) lines.push(`URL: ${url}`);
    for (const a of rawAttrs) {
      lines.push(`${a.title || a.id || 'Field'}: ${a.value}`);
    }
    if (topPassword) lines.push(`Password: ${topPassword}`);
    if (notesPlain) lines.push('', notesPlain);
    if (url) {
      addStd(
        'field_institution_domain',
        cleanInstitutionDomain(url) || url,
        'TEXT'
      );
    }
    addStd('field_markdown_note', lines.join('\n').trim(), 'MARKDOWN');
    return out;
  }

  if (url) {
    addStd(
      'field_institution_domain',
      cleanInstitutionDomain(url) || url,
      'TEXT'
    );
  }

  let firstName = '';
  let lastName = '';

  for (const { id: fid, title: fTitle, value } of rawAttrs) {
    const k = `${fid.toLowerCase()} ${fTitle.toLowerCase()}`;
    if (k.includes('firstname') || fid === 'firstname') {
      firstName = value;
    } else if (k.includes('lastname') || fid === 'lastname') {
      lastName = value;
    } else if (k.includes('cardholder')) {
      addStd('field_cardholder', value);
    } else if (k.includes('ccnum') || k.includes('card number')) {
      addStd('field_card_number', value, 'CARD_NUMBER');
    } else if (fid === 'type' && category === 'PAYMENT_CARD') {
      addStd('field_card_network', value);
    } else if (k.includes('validfrom')) {
      addStd('field_valid_from', value, 'DATE_ISO');
    } else if (k.includes('expiry') || k.includes('expiration')) {
      addStd('field_expiry_date', value, 'DATE_ISO');
    } else if (k.includes('cvv') || k.includes('verification number')) {
      addStd('field_cvv', value, 'SENSITIVE_SECRET');
    } else if (
      fid === 'pin' ||
      k.includes('telephonepin') ||
      k.includes('card pin')
    ) {
      addStd('field_pin', value, 'SENSITIVE_SECRET');
    } else if (
      k.includes('bankname') ||
      fid === 'bank' ||
      k.includes('issuing bank')
    ) {
      addStd('field_issuing_bank', value);
    } else if (k.includes('creditlimit')) {
      addStd('field_credit_limit', value);
    } else if (k.includes('iban')) {
      addStd('field_iban', value, 'IBAN');
    } else if (k.includes('swift') || k.includes('bic')) {
      addStd('field_bic', value);
    } else if (k.includes('accountno') || k.includes('account number')) {
      addStd('field_account_number', value, 'SENSITIVE_SECRET');
    } else if (k.includes('routingno') || k.includes('routing')) {
      addStd('field_routing_code', value);
    } else if (k.includes('accounttype')) {
      addStd('field_account_type', value);
    } else if (k.includes('owner')) {
      addStd('field_account_holder', value);
    } else if (
      k.includes('org_name') ||
      k.includes('organization') ||
      k.includes('company')
    ) {
      if (category === 'INSURANCE_CARD') addStd('field_insurer_name', value);
      else addStd('field_organization', value);
    } else if (k.includes('member_name')) {
      addStd('field_member_name', value);
    } else if (k.includes('membership_no') || k.includes('member id')) {
      addStd('field_membership_id', value);
    } else if (k.includes('member_since')) {
      addStd('field_member_since', value, 'DATE_ISO');
    } else if (k.includes('fullname')) {
      addStd('field_full_name', value);
    } else if (k.includes('birthdate') || fid === 'dob') {
      addStd('field_birthdate', value, 'DATE_ISO');
    } else if (k.includes('issuing_country')) {
      addStd('field_issuing_country', value);
    } else if (k.includes('nationality')) {
      addStd('field_nationality', value);
    } else if (k.includes('issuing_authority')) {
      addStd('field_issuing_authority', value);
    } else if (k.includes('issue_date')) {
      addStd('field_issue_date', value, 'DATE_ISO');
    } else if (fid === 'number' && category === 'OFFICIAL_DOCUMENT') {
      addStd('field_document_number', value, 'SENSITIVE_SECRET');
    } else if (fid === 'number' && category === 'INSURANCE_CARD') {
      addStd('field_insurance_number', value, 'SENSITIVE_SECRET');
    } else if (k.includes('address')) {
      if (category === 'PAYMENT_CARD')
        addStd('field_billing_address', value, 'MULTILINE_ADDRESS');
      else if (category === 'BANK_ACCOUNT')
        addStd('field_branch_address', value, 'MULTILINE_ADDRESS');
      else addStd('field_address', value, 'MULTILINE_ADDRESS');
    } else if (k.includes('email')) {
      addStd('field_email', value, 'EMAIL');
    } else if (k.includes('phone')) {
      addStd('field_support_phone', value, 'PHONE');
    } else {
      out.push({
        id: `f-1p-custom-${idx++}`,
        labelKey: fTitle || fid || 'Field',
        isCustomLabel: true,
        value: value.trim(),
        valueType: 'TEXT'
      });
    }
  }

  if (firstName || lastName) {
    addStd('field_full_name', `${firstName} ${lastName}`.trim());
  }
  if (notesPlain) {
    const noteKey =
      category === 'SECURE_NOTE' ? 'field_markdown_note' : 'field_notes';
    addStd(noteKey, notesPlain, 'MARKDOWN');
  }
  return out;
}

function serializeItemTo1PasswordObject(
  item: VaultItem,
  exportedSec: number
): Record<string, unknown> {
  const catUuid =
    item.category === 'PAYMENT_CARD'
      ? '002'
      : item.category === 'BANK_ACCOUNT'
        ? '104'
        : item.category === 'INSURANCE_CARD'
          ? '108'
          : item.category === 'MEMBERSHIP_CARD'
            ? '105'
            : item.category === 'IDENTITY'
              ? '004'
              : item.category === 'OFFICIAL_DOCUMENT'
                ? '109'
                : '003';

  const fv = (k: string) =>
    item.fields.find((f) => !f.isCustomLabel && f.labelKey === k)?.value ?? '';

  const sectionFields = item.fields
    .filter(
      (f) =>
        f.labelKey !== 'field_markdown_note' && f.labelKey !== 'field_notes'
    )
    .map((f) => {
      const onePassId = mapSafePocketKeyTo1PasswordFieldId(
        f.labelKey,
        Boolean(f.isCustomLabel)
      );
      const valTypeKey = f.isSensitive ? 'concealed' : 'string';
      return {
        id: onePassId,
        title: f.isCustomLabel ? f.labelKey : onePassId,
        value: { [valTypeKey]: f.value }
      };
    });

  const notesPlain = [fv('field_markdown_note'), fv('field_notes')]
    .filter((s) => s.trim().length > 0)
    .join('\n\n');

  const dom =
    fv('field_institution_domain') || item.cardTheme?.institutionDomain || '';

  return {
    uuid: item.id.replace(/^1p-/, ''),
    categoryUuid: catUuid,
    favIndex: item.favorite ? 1 : 0,
    createdAt: Math.floor(item.createdAtEpochMs / 1000),
    updatedAt: Math.max(Math.floor(item.updatedAtEpochMs / 1000), exportedSec),
    state: 'active',
    overview: {
      title: item.title,
      ainfo: item.subtitle,
      ...(dom
        ? { url: dom.startsWith('http') ? dom : `https://${dom}` }
        : {}),
      tags: item.tags
    },
    details: {
      notesPlain,
      sections: [
        {
          name: 'details',
          title: '',
          fields: sectionFields
        }
      ]
    }
  };
}

function mapSafePocketKeyTo1PasswordFieldId(
  key: string,
  isCustom: boolean
): string {
  if (isCustom) return key;
  switch (key) {
    case 'field_cardholder':
      return 'cardholder';
    case 'field_card_number':
      return 'ccnum';
    case 'field_card_network':
      return 'type';
    case 'field_valid_from':
      return 'validFrom';
    case 'field_expiry_date':
      return 'expiry';
    case 'field_cvv':
      return 'cvv';
    case 'field_pin':
      return 'pin';
    case 'field_issuing_bank':
      return 'bank';
    case 'field_credit_limit':
      return 'creditLimit';
    case 'field_iban':
      return 'iban';
    case 'field_bic':
      return 'swift';
    case 'field_account_holder':
      return 'owner';
    case 'field_account_number':
      return 'accountNo';
    case 'field_routing_code':
      return 'routingNo';
    case 'field_account_type':
      return 'accountType';
    case 'field_organization':
    case 'field_insurer_name':
      return 'org_name';
    case 'field_member_name':
      return 'member_name';
    case 'field_membership_id':
      return 'membership_no';
    case 'field_full_name':
      return 'fullname';
    case 'field_birthdate':
      return 'dob';
    case 'field_document_number':
    case 'field_insurance_number':
      return 'number';
    case 'field_issuing_country':
      return 'issuing_country';
    case 'field_issuing_authority':
      return 'issuing_authority';
    case 'field_nationality':
      return 'nationality';
    case 'field_issue_date':
      return 'issue_date';
    default:
      return key.replace(/^field_/, '');
  }
}

function parseSafePocketCsvRow(
  map: Map<string, string>,
  fallbackEpochMs: number
): VaultItem | null {
  const catRaw = getByAliases(map, 'category').toUpperCase() as VaultCategory;
  const category: VaultCategory = VAULT_CATEGORIES.includes(catRaw)
    ? catRaw
    : 'SECURE_NOTE';
  const title = getByAliases(map, 'title');
  if (!title) return null;
  const subtitle = getByAliases(map, 'subtitle');
  const id = getByAliases(map, 'id') || `sp-csv-${fallbackEpochMs}`;
  const favorite = getByAliases(map, 'favorite').toLowerCase() === 'true';
  const pinned = getByAliases(map, 'pinned').toLowerCase() === 'true';
  const tags = getByAliases(map, 'tags')
    .split(/[|,]/)
    .map((s) => s.trim())
    .filter(Boolean);

  let fields: VaultField[] = [];
  const fieldsJson = getByAliases(map, 'fields_json');
  if (fieldsJson) {
    try {
      fields = JSON.parse(fieldsJson) as VaultField[];
    } catch {
      fields = [];
    }
  } else {
    const summary = getByAliases(map, 'fields_summary');
    fields = summary
      .split('; ')
      .map((pair, i): VaultField | null => {
        const eq = pair.indexOf('=');
        if (eq <= 0) return null;
        const labelKey = pair.slice(0, eq).trim();
        const value = pair.slice(eq + 1).trim();
        return {
          id: `f-sp-${i}`,
          labelKey,
          isCustomLabel: !labelKey.startsWith('field_'),
          value,
          valueType: 'TEXT',
          isSensitive: SENSITIVE_FIELD_KEYS.has(labelKey)
        };
      })
      .filter((x): x is VaultField => x !== null);
  }

  return {
    schemaVersion: 1,
    id,
    category,
    title,
    subtitle,
    favorite,
    pinned,
    tags,
    fields,
    attachments: [],
    createdAtEpochMs: fallbackEpochMs,
    updatedAtEpochMs: fallbackEpochMs
  };
}

function inferCategoryFromCsvRow(
  rawType: string,
  map: Map<string, string>
): VaultCategory {
  if (
    rawType.includes('card') &&
    !rawType.includes('insur') &&
    !rawType.includes('member')
  ) {
    return 'PAYMENT_CARD';
  }
  if (
    rawType.includes('kreditkarte') ||
    rawType.includes('credit') ||
    rawType.includes('debit')
  ) {
    return 'PAYMENT_CARD';
  }
  if (rawType.includes('bank') || rawType.includes('konto')) {
    return 'BANK_ACCOUNT';
  }
  if (
    rawType.includes('insur') ||
    rawType.includes('medical') ||
    rawType.includes('versicherung') ||
    rawType.includes('krankenkasse')
  ) {
    return 'INSURANCE_CARD';
  }
  if (
    rawType.includes('member') ||
    rawType.includes('reward') ||
    rawType.includes('loyalty') ||
    rawType.includes('mitglied')
  ) {
    return 'MEMBERSHIP_CARD';
  }
  if (rawType.includes('identit')) {
    return 'IDENTITY';
  }
  if (
    rawType.includes('passport') ||
    rawType.includes('driver') ||
    rawType.includes('license') ||
    rawType.includes('reisepass') ||
    rawType.includes('ausweis') ||
    rawType.includes('ssn')
  ) {
    return 'OFFICIAL_DOCUMENT';
  }
  if (
    rawType.includes('note') ||
    rawType.includes('notiz') ||
    rawType.includes('login')
  ) {
    return 'SECURE_NOTE';
  }
  if (getByAliases(map, 'number', 'card number', 'ccnum', 'kartennummer')) {
    return 'PAYMENT_CARD';
  }
  if (getByAliases(map, 'iban')) return 'BANK_ACCOUNT';
  if (getByAliases(map, 'membership id', 'mitgliedsnummer')) {
    return 'MEMBERSHIP_CARD';
  }
  return 'SECURE_NOTE';
}

function buildFieldsFromCsvRow(
  category: VaultCategory,
  map: Map<string, string>,
  isLogin: boolean
): VaultField[] {
  const out: VaultField[] = [];
  let idx = 0;

  const add = (
    key: string,
    v: string,
    valueType: FieldValueType = 'TEXT'
  ) => {
    const trimmed = v.trim();
    if (!trimmed || out.some((f) => f.labelKey === key)) return;
    out.push({
      id: `f-csv-${idx++}`,
      labelKey: key,
      value: trimmed,
      valueType,
      isSensitive:
        valueType === 'SENSITIVE_SECRET' || SENSITIVE_FIELD_KEYS.has(key)
    });
  };

  if (isLogin) {
    const u = getByAliases(map, 'username', 'benutzername');
    const p = getByAliases(map, 'password', 'passwort');
    const url = getByAliases(map, 'url', 'website');
    const n = getByAliases(map, 'notes', 'notizen');
    if (url) {
      add('field_institution_domain', cleanInstitutionDomain(url) || url);
    }
    const md = [
      url ? `URL: ${url}` : '',
      u ? `Username: ${u}` : '',
      p ? `Password: ${p}` : '',
      n
    ]
      .filter(Boolean)
      .join('\n');
    add('field_markdown_note', md, 'MARKDOWN');
    return out;
  }

  add(
    'field_cardholder',
    getByAliases(map, 'cardholder name', 'cardholder', 'karteninhaber')
  );
  add(
    'field_card_number',
    getByAliases(map, 'number', 'card number', 'ccnum', 'kartennummer'),
    'CARD_NUMBER'
  );
  add(
    'field_card_network',
    getByAliases(map, 'brand', 'network', 'kartennetzwerk')
  );
  add(
    'field_valid_from',
    getByAliases(map, 'valid from', 'gültig ab'),
    'DATE_ISO'
  );
  add(
    'field_expiry_date',
    getByAliases(
      map,
      'expiry date',
      'expiration date',
      'ablaufdatum',
      'gültig bis'
    ),
    'DATE_ISO'
  );
  add(
    'field_cvv',
    getByAliases(map, 'verification number', 'cvv', 'cvc'),
    'SENSITIVE_SECRET'
  );
  add('field_pin', getByAliases(map, 'pin'), 'SENSITIVE_SECRET');

  if (category === 'INSURANCE_CARD') {
    add(
      'field_insurer_name',
      getByAliases(
        map,
        'issuing bank',
        'insurer',
        'provider',
        'krankenkasse',
        'versicherung'
      )
    );
    add(
      'field_insurance_number',
      getByAliases(
        map,
        'account number',
        'policy number',
        'versichertennummer'
      ),
      'SENSITIVE_SECRET'
    );
  } else {
    add(
      'field_issuing_bank',
      getByAliases(map, 'issuing bank', 'bank', 'bank name')
    );
    add(
      'field_account_number',
      getByAliases(map, 'account number', 'kontonummer'),
      'SENSITIVE_SECRET'
    );
  }

  add(
    'field_account_holder',
    getByAliases(map, 'account holder', 'owner', 'kontoinhaber')
  );
  add('field_iban', getByAliases(map, 'iban'), 'IBAN');
  add('field_bic', getByAliases(map, 'swift', 'bic'));
  add('field_routing_code', getByAliases(map, 'routing number', 'blz'));
  add(
    'field_full_name',
    getByAliases(map, 'full name', 'fullname', 'name_full')
  );
  add(
    'field_birthdate',
    getByAliases(map, 'birthdate', 'date of birth', 'geburtsdatum'),
    'DATE_ISO'
  );
  add(
    'field_document_number',
    getByAliases(map, 'document number', 'ausweisnummer'),
    'SENSITIVE_SECRET'
  );
  add(
    'field_organization',
    getByAliases(map, 'organization', 'company', 'verein')
  );
  add(
    'field_membership_id',
    getByAliases(map, 'membership id', 'member id', 'mitgliedsnummer')
  );
  add(
    'field_institution_domain',
    getByAliases(map, 'website', 'url', 'domain')
  );
  add('field_email', getByAliases(map, 'email', 'e-mail'), 'EMAIL');
  add('field_phone', getByAliases(map, 'phone', 'telefon'), 'PHONE');
  add(
    'field_address',
    getByAliases(map, 'address', 'adresse'),
    'MULTILINE_ADDRESS'
  );

  const notes = getByAliases(map, 'notes', 'notizen', 'markdown_note');
  if (notes) {
    add(
      category === 'SECURE_NOTE' ? 'field_markdown_note' : 'field_notes',
      notes,
      'MARKDOWN'
    );
  }
  return out;
}

function getByAliases(map: Map<string, string>, ...aliases: string[]): string {
  const lowerAliases = aliases.map((a) => a.toLowerCase());
  for (const [k, v] of map.entries()) {
    if (lowerAliases.includes(k.toLowerCase()) && v.trim()) {
      return v.trim();
    }
  }
  return '';
}

export function parseRfc4180Csv(input: string): string[][] {
  const rows: string[][] = [];
  let currentRow: string[] = [];
  let cell = '';
  let inQuotes = false;

  const firstLineEnd = input.indexOf('\n');
  const firstLine = firstLineEnd >= 0 ? input.slice(0, firstLineEnd) : input;
  const semiCount = (firstLine.match(/;/g) ?? []).length;
  const commaCount = (firstLine.match(/,/g) ?? []).length;
  const delimiter = semiCount > commaCount ? ';' : ',';

  for (let i = 0; i < input.length; i++) {
    const ch = input[i];
    if (inQuotes) {
      if (ch === '"') {
        if (i + 1 < input.length && input[i + 1] === '"') {
          cell += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        cell += ch;
      }
    } else if (ch === '"') {
      inQuotes = true;
    } else if (ch === delimiter) {
      currentRow.push(cell);
      cell = '';
    } else if (ch === '\r') {
      if (i + 1 < input.length && input[i + 1] === '\n') i++;
      currentRow.push(cell);
      cell = '';
      rows.push(currentRow);
      currentRow = [];
    } else if (ch === '\n') {
      currentRow.push(cell);
      cell = '';
      rows.push(currentRow);
      currentRow = [];
    } else {
      cell += ch;
    }
  }
  if (cell.length > 0 || currentRow.length > 0) {
    currentRow.push(cell);
    rows.push(currentRow);
  }
  return rows;
}

function escCsv(value: string): string {
  return `"${value.replaceAll('"', '""')}"`;
}

// ============================================================================
// PKZIP READER (STORED + DEFLATE VIA CENTRAL DIRECTORY) & STORED WRITER
// ============================================================================

async function extractZipEntriesAsync(
  zip: Uint8Array
): Promise<Map<string, Uint8Array>> {
  const out = new Map<string, Uint8Array>();

  // Locate End of Central Directory (0x06054b50) from tail so streamed ZIPs work reliably
  let eocdOffset = -1;
  const minEocd = Math.max(0, zip.byteLength - 65_557);
  for (let i = zip.byteLength - 22; i >= minEocd; i--) {
    if (readLe32(zip, i) === 0x06054b50) {
      eocdOffset = i;
      break;
    }
  }

  if (eocdOffset >= 0) {
    const totalEntries = readLe16(zip, eocdOffset + 10);
    let cdPos = readLe32(zip, eocdOffset + 16);
    for (let idx = 0; idx < totalEntries && cdPos + 46 <= zip.byteLength; idx++) {
      if (readLe32(zip, cdPos) !== 0x02014b50) break;
      const method = readLe16(zip, cdPos + 10);
      const compSize = readLe32(zip, cdPos + 20);
      const nameLen = readLe16(zip, cdPos + 28);
      const extraLen = readLe16(zip, cdPos + 30);
      const commentLen = readLe16(zip, cdPos + 32);
      const localHeaderOffset = readLe32(zip, cdPos + 42);
      const name = new TextDecoder().decode(
        zip.subarray(cdPos + 46, cdPos + 46 + nameLen)
      );

      if (
        localHeaderOffset + 30 <= zip.byteLength &&
        readLe32(zip, localHeaderOffset) === 0x04034b50
      ) {
        const localNameLen = readLe16(zip, localHeaderOffset + 26);
        const localExtraLen = readLe16(zip, localHeaderOffset + 28);
        const dataStart = localHeaderOffset + 30 + localNameLen + localExtraLen;
        const slice = zip.subarray(dataStart, dataStart + compSize);
        if (method === 0) {
          out.set(name, slice);
        } else if (method === 8) {
          out.set(name, await inflateRawBytes(slice));
        }
      }
      cdPos += 46 + nameLen + extraLen + commentLen;
    }
    return out;
  }

  // Fallback linear local header scan
  let pos = 0;
  while (pos + 30 <= zip.byteLength) {
    if (readLe32(zip, pos) !== 0x04034b50) break;
    const method = readLe16(zip, pos + 8);
    const compSize = readLe32(zip, pos + 18);
    const nameLen = readLe16(zip, pos + 26);
    const extraLen = readLe16(zip, pos + 28);
    const dataStart = pos + 30 + nameLen + extraLen;
    if (dataStart + compSize > zip.byteLength) break;
    const name = new TextDecoder().decode(zip.subarray(pos + 30, pos + 30 + nameLen));
    const slice = zip.subarray(dataStart, dataStart + compSize);
    if (method === 0) {
      out.set(name, slice);
    } else if (method === 8) {
      out.set(name, await inflateRawBytes(slice));
    }
    pos = dataStart + compSize;
  }
  return out;
}

async function inflateRawBytes(compressed: Uint8Array): Promise<Uint8Array> {
  if (typeof DecompressionStream !== 'undefined') {
    const ds = new DecompressionStream('deflate-raw');
    const writer = ds.writable.getWriter();
    await writer.write(compressed as unknown as BufferSource);
    await writer.close();
    const res = new Response(ds.readable);
    return new Uint8Array(await res.arrayBuffer());
  }
  throw new Error('DEFLATE decompression stream unavailable in this runtime');
}

function createStoredZipArchive(entries: Map<string, Uint8Array>): Uint8Array {
  const enc = new TextEncoder();
  const localChunks: Uint8Array[] = [];
  const centralChunks: Uint8Array[] = [];
  let offset = 0;

  for (const [name, data] of entries.entries()) {
    const nameBytes = enc.encode(name);
    const crc = crc32(data);

    const local = new Uint8Array(30 + nameBytes.byteLength + data.byteLength);
    putLe32(local, 0, 0x04034b50);
    putLe16(local, 4, 20);
    putLe16(local, 6, 0x0800);
    putLe16(local, 8, 0);
    putLe16(local, 10, 0);
    putLe16(local, 12, 0);
    putLe32(local, 14, crc);
    putLe32(local, 18, data.byteLength);
    putLe32(local, 22, data.byteLength);
    putLe16(local, 26, nameBytes.byteLength);
    putLe16(local, 28, 0);
    local.set(nameBytes, 30);
    local.set(data, 30 + nameBytes.byteLength);
    localChunks.push(local);

    const central = new Uint8Array(46 + nameBytes.byteLength);
    putLe32(central, 0, 0x02014b50);
    putLe16(central, 4, 20);
    putLe16(central, 6, 20);
    putLe16(central, 8, 0x0800);
    putLe16(central, 10, 0);
    putLe16(central, 12, 0);
    putLe16(central, 14, 0);
    putLe32(central, 16, crc);
    putLe32(central, 20, data.byteLength);
    putLe32(central, 24, data.byteLength);
    putLe16(central, 28, nameBytes.byteLength);
    putLe16(central, 30, 0);
    putLe16(central, 32, 0);
    putLe16(central, 34, 0);
    putLe16(central, 36, 0);
    putLe32(central, 38, 0);
    putLe32(central, 42, offset);
    central.set(nameBytes, 46);
    centralChunks.push(central);

    offset += local.byteLength;
  }

  const centralSize = centralChunks.reduce((s, c) => s + c.byteLength, 0);
  const eocd = new Uint8Array(22);
  putLe32(eocd, 0, 0x06054b50);
  putLe16(eocd, 8, entries.size);
  putLe16(eocd, 10, entries.size);
  putLe32(eocd, 12, centralSize);
  putLe32(eocd, 16, offset);

  const out = new Uint8Array(offset + centralSize + 22);
  let pos = 0;
  for (const c of localChunks) {
    out.set(c, pos);
    pos += c.byteLength;
  }
  for (const c of centralChunks) {
    out.set(c, pos);
    pos += c.byteLength;
  }
  out.set(eocd, pos);
  return out;
}

function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff;
  for (let i = 0; i < bytes.byteLength; i++) {
    crc ^= bytes[i];
    for (let k = 0; k < 8; k++) {
      crc = (crc & 1) !== 0 ? (crc >>> 1) ^ 0xedb88320 : crc >>> 1;
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function putLe16(b: Uint8Array, o: number, v: number): void {
  b[o] = v & 0xff;
  b[o + 1] = (v >>> 8) & 0xff;
}

function putLe32(b: Uint8Array, o: number, v: number): void {
  b[o] = v & 0xff;
  b[o + 1] = (v >>> 8) & 0xff;
  b[o + 2] = (v >>> 16) & 0xff;
  b[o + 3] = (v >>> 24) & 0xff;
}

function readLe16(b: Uint8Array, o: number): number {
  return b[o] | (b[o + 1] << 8);
}

function readLe32(b: Uint8Array, o: number): number {
  return (
    (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0
  );
}
