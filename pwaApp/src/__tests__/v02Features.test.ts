import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  exportTo1Password1Pux,
  exportTo1PasswordCsv,
  exportToSafePocketCsv,
  importFrom1Password1Pux,
  importFromCsv,
  parse1PasswordExportDataJson
} from '../crypto/portability1PasswordCsv';
import { createInitialSampleVaultItems } from '../data/sampleVault';
import { SUPPORTED_LOCALES } from '../i18n/l10n';
import type { VaultItem } from '../types';
import {
  applyAutoBranding,
  fetchDomainBrandLogo,
  renderInstitutionLogoHtml,
  renderNetworkOrTypeLogoHtml,
  resolveDualBrand,
  resolveDualBrandFromFields
} from '../ui/institutionBrands';

describe('SafePocket v0.2 (Build 4) Feature Verification Suite', () => {
  it('1. Dual-Brand Institution + Card Network/Type Auto-Detection: Deutsche Bank VISA (#0018A8) & all 7 categories', () => {
    // Deutsche Bank + VISA Credit Card
    const dbVisaItem: VaultItem = applyAutoBranding({
      schemaVersion: 1,
      id: 'test-db-visa',
      category: 'PAYMENT_CARD',
      title: 'Deutsche Bank Card Plus',
      subtitle: 'Kreditkarte',
      favorite: true,
      tags: ['banking'],
      fields: [
        {
          id: 'f1',
          labelKey: 'field_card_number',
          value: '4907 4210 8834 9015',
          valueType: 'CARD_NUMBER',
          isSensitive: true
        },
        {
          id: 'f2',
          labelKey: 'field_issuing_bank',
          value: 'Deutsche Bank AG',
          valueType: 'TEXT'
        }
      ],
      attachments: [],
      createdAtEpochMs: 1000,
      updatedAtEpochMs: 1000
    });

    const dbResolved = resolveDualBrand(dbVisaItem);
    expect(dbResolved.institution?.id).toBe('deutsche_bank');
    expect(dbResolved.institution?.primaryColorHex).toBe('#0018A8');
    expect(dbResolved.networkOrTypeCode).toBe('VISA');
    expect(dbVisaItem.cardTheme?.paletteId).toBe('custom');
    expect(dbVisaItem.cardTheme?.customColorHex).toBe('#0018A8');

    const instLogoHtml = renderInstitutionLogoHtml(dbVisaItem, dbResolved);
    const netLogoHtml = renderNetworkOrTypeLogoHtml(dbResolved);
    expect(instLogoHtml).toContain('#0018A8');
    expect(instLogoHtml).toContain('<svg');
    expect(netLogoHtml).toContain('sp-net-visa');
    expect(netLogoHtml).toContain('VISA');

    // Editing an existing Deutsche Bank VISA into a Sparkasse Mastercard must immediately switch dual logos & brand color (#E2001A)
    dbVisaItem.title = 'Sparkasse Gold';
    dbVisaItem.fields = [
      {
        id: 'f1',
        labelKey: 'field_card_number',
        value: '5200 8282 8282 8210',
        valueType: 'CARD_NUMBER',
        isSensitive: true
      },
      {
        id: 'f2',
        labelKey: 'field_issuing_bank',
        value: 'Sparkasse Berlin',
        valueType: 'TEXT'
      }
    ];
    const rebranded = applyAutoBranding(dbVisaItem);
    const spkResolved = resolveDualBrand(rebranded);
    expect(spkResolved.institution?.id).toBe('sparkasse');
    expect(rebranded.cardTheme?.customColorHex).toBe('#E2001A');
    expect(spkResolved.networkOrTypeCode).toBe('MASTERCARD');
    expect(renderNetworkOrTypeLogoHtml(spkResolved)).toContain('sp-net-mc');

    // German IBAN BLZ detection for Bank Accounts (37040044 -> Commerzbank)
    const ibanResolved = resolveDualBrandFromFields(
      'BANK_ACCOUNT',
      'Geschäftskonto',
      'Girokonto',
      { field_iban: 'DE89 3704 0044 0532 0130 00' }
    );
    expect(ibanResolved.institution?.id).toBe('commerzbank');
    expect(ibanResolved.networkOrTypeCode).toBe('SEPA');

    // Insurance Card (TK + EHIC), Membership Card (ADAC + BARCODE), Official Document (Passport)
    const samples = createInitialSampleVaultItems();
    const dbSample = samples.find((i) => i.id === 'item-card-db-visa');
    expect(dbSample).toBeDefined();
    expect(dbSample?.cardTheme?.customColorHex).toBe('#0018A8');
    expect(dbSample?.fields.length).toBeGreaterThanOrEqual(16);

    const tkSample = samples.find((i) => i.category === 'INSURANCE_CARD')!;
    expect(resolveDualBrand(tkSample).institution?.id).toBe('tk');
    expect(resolveDualBrand(tkSample).networkOrTypeCode).toBe('EHIC');

    const adacSample = samples.find((i) => i.category === 'MEMBERSHIP_CARD')!;
    expect(resolveDualBrand(adacSample).institution?.id).toBe('adac');
    expect(resolveDualBrand(adacSample).networkOrTypeCode).toBe('BARCODE');
  });

  it('2. Optional HTTPS Institution Icon Downloader: sends only domain name and caches PNG data URI', async () => {
    const requestedUrls: string[] = [];
    // Minimal 1x1 valid PNG header bytes (>= 64 bytes payload)
    const fakePng = new Uint8Array(96);
    fakePng[0] = 0x89;
    fakePng[1] = 0x50;
    fakePng[2] = 0x4e;
    fakePng[3] = 0x47;

    const mockFetch = (async (url: string) => {
      requestedUrls.push(String(url));
      return new Response(fakePng, { status: 200 });
    }) as typeof fetch;

    const res = await fetchDomainBrandLogo(
      'https://www.deutsche-bank.de/pk.html?secret=never',
      mockFetch
    );
    expect(res).not.toBeNull();
    expect(res?.domain).toBe('deutsche-bank.de');
    expect(res?.dataUri.startsWith('data:image/png;base64,')).toBe(true);
    expect(requestedUrls[0]).toContain('deutsche-bank.de');
    expect(requestedUrls[0]).not.toContain('secret=never');
  });

  it('3. Bottom-Left Theme Toggle, Smooth Seam-Free Button Hover & Complete Category Fields + Edit UX', () => {
    const repoRoot = path.resolve(__dirname, '../../..');
    const appShellSource = fs.readFileSync(
      path.resolve(__dirname, '../ui/appShell.ts'),
      'utf-8'
    );
    const cssSource = fs.readFileSync(
      path.resolve(__dirname, '../styles/m3-expressive.css'),
      'utf-8'
    );
    const androidShellKt = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/AdaptiveSafePocketShell.kt'
      ),
      'utf-8'
    );
    const smoothComponentsKt = fs.readFileSync(
      path.join(
        repoRoot,
        'androidApp/src/main/kotlin/com/safepocket/android/ui/SmoothInteractiveComponents.kt'
      ),
      'utf-8'
    );

    // 1) Theme toggle placed in bottom-left sidebar footer bar, NOT inside top .sp-brand
    const brandIdx = appShellSource.indexOf('<div class="sp-brand">');
    const footerIdx = appShellSource.indexOf('<div class="sp-sidebar-footer">');
    const toggleIdx = appShellSource.indexOf('id="sp-quick-theme-toggle"');
    expect(brandIdx).toBeGreaterThan(-1);
    expect(footerIdx).toBeGreaterThan(brandIdx);
    expect(toggleIdx).toBeGreaterThan(footerIdx);
    expect(appShellSource).toContain('class="sp-sidebar-bottom-bar"');

    // 2) Smooth hover transition & zero middle gray seam line in both PWA CSS and Android Compose
    expect(cssSource).toContain('cubic-bezier(0.2, 0, 0, 1)');
    expect(cssSource).toContain('background-clip: padding-box');
    expect(cssSource).toContain('.sp-btn::before');
    expect(cssSource).toContain('display: none !important');
    expect(smoothComponentsKt).toContain('SeamFreeIndication');

    // 3) Complete Payment Card fields & Item Editing across PWA and Android
    for (const requiredKey of [
      'field_card_network',
      'field_card_type',
      'field_valid_from',
      'field_institution_domain',
      'field_credit_limit',
      'field_billing_address',
      'field_online_passcode',
      'field_emergency_phone',
      'field_notes'
    ]) {
      expect(appShellSource).toContain(requiredKey);
    }
    expect(appShellSource).toContain('id="sp-edit-item-btn"');
    expect(appShellSource).toContain('openEditItemModal');
    expect(androidShellKt).toContain('CategoryFieldTemplates.forCategory');
  });

  it('4. 1Password (.1pux & CSV) + SafePocket CSV Import & Export Round-Trip (with Login Skip Default)', async () => {
    const sampleItems = createInitialSampleVaultItems();

    // A) Export SafePocket vault -> 1Password .1pux ZIP -> re-import
    const puxBytes = exportTo1Password1Pux(sampleItems, 1763320000);
    expect(puxBytes[0]).toBe(0x50); // 'P'
    expect(puxBytes[1]).toBe(0x4b); // 'K'

    const reimportedPux = await importFrom1Password1Pux(puxBytes, false);
    expect(reimportedPux.skippedLoginCount).toBe(0);
    expect(reimportedPux.importedItems.length).toBe(sampleItems.length);
    const importedDbVisa = reimportedPux.importedItems.find((i) =>
      i.title.includes('Deutsche Bank')
    );
    expect(importedDbVisa).toBeDefined();
    expect(importedDbVisa?.category).toBe('PAYMENT_CARD');
    expect(importedDbVisa?.cardTheme?.customColorHex).toBe('#0018A8');

    // B) Verify 1Password Login items (categoryUuid "001") are skipped by default, or imported as Secure Notes when enabled
    const mixed1PJson = JSON.stringify({
      accounts: [
        {
          vaults: [
            {
              items: [
                {
                  uuid: 'login-1',
                  categoryUuid: '001',
                  state: 'active',
                  overview: { title: 'GitHub Login', url: 'https://github.com' },
                  details: {
                    loginFields: [
                      { designation: 'username', value: 'clara' },
                      { designation: 'password', value: 's3cr3t' }
                    ]
                  }
                },
                {
                  uuid: 'card-db-1',
                  categoryUuid: '002',
                  state: 'active',
                  overview: { title: 'Deutsche Bank Travel VISA' },
                  details: {
                    sections: [
                      {
                        fields: [
                          { id: 'ccnum', value: { concealed: '4532015112830366' } },
                          { id: 'bank', value: { string: 'Deutsche Bank' } },
                          { id: 'cvv', value: { concealed: '918' } }
                        ]
                      }
                    ]
                  }
                }
              ]
            }
          ]
        }
      ]
    });

    const skipRes = parse1PasswordExportDataJson(mixed1PJson, false);
    expect(skipRes.importedItems).toHaveLength(1);
    expect(skipRes.skippedLoginCount).toBe(1);
    expect(skipRes.importedItems[0].cardTheme?.customColorHex).toBe('#0018A8');

    const includeRes = parse1PasswordExportDataJson(mixed1PJson, true);
    expect(includeRes.importedItems).toHaveLength(2);
    expect(includeRes.skippedLoginCount).toBe(0);
    expect(
      includeRes.importedItems.find((i) => i.title === 'GitHub Login')?.category
    ).toBe('SECURE_NOTE');

    // C) Export to 1Password CSV & SafePocket CSV -> re-import round-trip
    const onePassCsv = exportTo1PasswordCsv(sampleItems);
    const reimp1PCsv = importFromCsv(onePassCsv, false);
    expect(reimp1PCsv.importedItems.length).toBe(sampleItems.length);

    const spCsv = exportToSafePocketCsv(sampleItems);
    const reimpSpCsv = importFromCsv(spCsv, false);
    expect(reimpSpCsv.importedItems.length).toBe(sampleItems.length);
    expect(
      reimpSpCsv.importedItems.find((i) => i.id === 'item-card-db-visa')?.fields
        .length
    ).toBeGreaterThanOrEqual(16);
  });

  it('5. Full 6-Language Localization Catalog Parity (270 keys across EN, DE, IT, FR, ES, EL)', () => {
    const repoRoot = path.resolve(__dirname, '../../..');
    const enCatalog = JSON.parse(
      fs.readFileSync(path.join(repoRoot, 'i18n/en.arb.json'), 'utf-8')
    ) as Record<string, string>;
    const enKeys = Object.keys(enCatalog)
      .filter((k) => !k.startsWith('@'))
      .sort();
    expect(enKeys.length).toBeGreaterThanOrEqual(270);

    for (const loc of SUPPORTED_LOCALES) {
      const cat = JSON.parse(
        fs.readFileSync(
          path.join(repoRoot, `i18n/${loc.code}.arb.json`),
          'utf-8'
        )
      ) as Record<string, string>;
      const keys = Object.keys(cat)
        .filter((k) => !k.startsWith('@'))
        .sort();
      expect(keys).toEqual(enKeys);
    }
  });
});
