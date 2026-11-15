import Foundation
import CryptoKit
import Security
import LocalAuthentication

/// Native macOS Security & CryptoKit Service:
/// 1. Provides hardware-accelerated AES-256-GCM (`CryptoKit.AES.GCM`) & HKDF-SHA256 to `SafePocketShared.xcframework`.
/// 2. Stores the wrapped Vault Encryption Key inside the macOS Data Protection Keychain gated by Touch ID (`biometryCurrentSet`).
final class MacSecurityService {
    private let keychainService = "com.safepocket.macos.vaultkey"
    private let keychainAccount = "primary-vault-vek"

    func sealAes256Gcm(keyBytes: Data, nonceBytes: Data, plaintext: Data, aad: Data) throws -> Data {
        let key = SymmetricKey(data: keyBytes)
        let nonce = try AES.GCM.Nonce(data: nonceBytes)
        let sealed = try AES.GCM.seal(plaintext, using: key, nonce: nonce, authenticating: aad)
        return sealed.ciphertext + sealed.tag
    }

    func openAes256Gcm(keyBytes: Data, nonceBytes: Data, ciphertextAndTag: Data, aad: Data) throws -> Data {
        let key = SymmetricKey(data: keyBytes)
        let nonce = try AES.GCM.Nonce(data: nonceBytes)
        let tagStart = ciphertextAndTag.count - 16
        let box = try AES.GCM.SealedBox(
            nonce: nonce,
            ciphertext: ciphertextAndTag.prefix(tagStart),
            tag: ciphertextAndTag.suffix(16)
        )
        return try AES.GCM.open(box, using: key, authenticating: aad)
    }

    func storeVaultKeyInTouchIdKeychain(_ vek: Data) throws {
        var error: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            .biometryCurrentSet,
            &error
        ) else {
            throw error!.takeRetainedValue() as Error
        }

        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount
        ]
        SecItemDelete(deleteQuery as CFDictionary)

        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecAttrAccessControl as String: access,
            kSecValueData as String: vek
        ]
        let status = SecItemAdd(addQuery as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
    }

    func loadVaultKeyWithTouchId(localizedPrompt: String) throws -> Data {
        let context = LAContext()
        context.localizedReason = localizedPrompt

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keychainAccount,
            kSecReturnData as String: true,
            kSecUseAuthenticationContext as String: context
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
        return data
    }
}
