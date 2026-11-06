/**
 * Configuration Manager
 * Secure storage and management of VPN configurations
 * For legitimate use with authorized servers only
 */

import { tunnelManager, type TunnelConfig, createDefaultConfig, validateTunnelConfig } from "./tunnel-engine";
import { wireguardProfileManager, wireguardConnectionManager, type WireGuardProfile } from "./wireguard-enhanced";
import { SAMPLE_VPS_SERVERS, type RelayServer } from "./servers";

// ============================================================================
// Configuration Types
// ============================================================================

export type AppConfig = {
  version: string;
  lastUpdated: number;
  defaultProtocol: string;
  autoConnect: boolean;
  autoReconnect: boolean;
  reconnectInterval: number;
  theme: "light" | "dark" | "system";
  diagnosticsEnabled: boolean;
};

export type ProfileConfig = {
  id: string;
  name: string;
  type: "tunnel" | "wireguard" | "relay";
  config: TunnelConfig | WireGuardProfile | RelayServer;
  createdAt: number;
  lastUsed: number;
  isFavorite: boolean;
  tags: string[];
};

export type ConnectionHistory = {
  id: string;
  profileId: string;
  connectedAt: number;
  disconnectedAt: number;
  duration: number;
  bytesSent: number;
  bytesReceived: number;
  success: boolean;
  error?: string;
};

// ============================================================================
// Default Configuration
// ============================================================================

const DEFAULT_APP_CONFIG: AppConfig = {
  version: "1.0.0",
  lastUpdated: Date.now(),
  defaultProtocol: "tls",
  autoConnect: false,
  autoReconnect: true,
  reconnectInterval: 5,
  theme: "system",
  diagnosticsEnabled: true,
};

// ============================================================================
// Configuration Storage
// ============================================================================

class LocalStorageWrapper {
  private prefix: string;
  
  constructor(prefix: string = "vpn-") {
    this.prefix = prefix;
  }
  
  get<T>(key: string): T | null {
    try {
      const item = localStorage.getItem(`${this.prefix}${key}`);
      return item ? JSON.parse(item) : null;
    } catch {
      return null;
    }
  }
  
  set<T>(key: string, value: T): void {
    try {
      localStorage.setItem(`${this.prefix}${key}`, JSON.stringify(value));
    } catch {
      // Storage may be blocked
    }
  }
  
  remove(key: string): void {
    try {
      localStorage.removeItem(`${this.prefix}${key}`);
    } catch {
      // Ignore errors
    }
  }
  
  clear(): void {
    try {
      // Clear all items with our prefix
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (key?.startsWith(this.prefix)) {
          localStorage.removeItem(key);
        }
      }
    } catch {
      // Ignore errors
    }
  }
}

// ============================================================================
// App Configuration Manager
// ============================================================================

export class AppConfigManager {
  private storage: LocalStorageWrapper;
  private config: AppConfig;
  
  constructor() {
    this.storage = new LocalStorageWrapper("app-");
    this.config = this.loadConfig();
  }
  
  private loadConfig(): AppConfig {
    const saved = this.storage.get<AppConfig>("config");
    if (saved) {
      return { ...DEFAULT_APP_CONFIG, ...saved };
    }
    return { ...DEFAULT_APP_CONFIG };
  }
  
  saveConfig(): void {
    this.storage.set("config", this.config);
  }
  
  getConfig(): AppConfig {
    return { ...this.config };
  }
  
  updateConfig(updates: Partial<AppConfig>): void {
    this.config = { ...this.config, ...updates, lastUpdated: Date.now() };
    this.saveConfig();
  }
  
  resetConfig(): void {
    this.config = { ...DEFAULT_APP_CONFIG };
    this.saveConfig();
  }
  
  // Convenience methods
  get<T extends keyof AppConfig>(key: T): AppConfig[T] {
    return this.config[key];
  }
  
  set<T extends keyof AppConfig>(key: T, value: AppConfig[T]): void {
    this.config[key] = value;
    this.config.lastUpdated = Date.now();
    this.saveConfig();
  }
}

export const appConfigManager = new AppConfigManager();

// ============================================================================
// Profile Configuration Manager
// ============================================================================

export class ProfileConfigManager {
  private storage: LocalStorageWrapper;
  private profiles: Map<string, ProfileConfig> = new Map();
  
  constructor() {
    this.storage = new LocalStorageWrapper("profile-");
    this.loadProfiles();
  }
  
  private loadProfiles(): void {
    try {
      const saved = this.storage.get<ProfileConfig[]>("all");
      if (saved) {
        for (const profile of saved) {
          this.profiles.set(profile.id, profile);
        }
      }
      
      // Add sample VPS servers as default profiles
      for (const server of SAMPLE_VPS_SERVERS) {
        if (!Array.from(this.profiles.values()).some((p) => p.id === server.id)) {
          const profile: ProfileConfig = {
            id: server.id,
            name: server.name,
            type: "relay",
            config: server,
            createdAt: 0,
            lastUsed: 0,
            isFavorite: false,
            tags: ["sample", "vps"],
          };
          this.profiles.set(profile.id, profile);
        }
      }
      this.saveProfiles();
    } catch {
      // Ignore errors
    }
  }
  
  private saveProfiles(): void {
    try {
      const profiles = Array.from(this.profiles.values());
      this.storage.set("all", profiles);
    } catch {
      // Ignore errors
    }
  }
  
  // Create a new profile
  createProfile(profile: Omit<ProfileConfig, "id" | "createdAt" | "lastUsed">): ProfileConfig {
    const id = `profile-${Date.now()}`;
    const newProfile: ProfileConfig = {
      ...profile,
      id,
      createdAt: Date.now(),
      lastUsed: 0,
    };
    
    this.profiles.set(id, newProfile);
    this.saveProfiles();
    return newProfile;
  }
  
  // Create from tunnel config
  createTunnelProfile(config: TunnelConfig): ProfileConfig {
    const profile: ProfileConfig = {
      id: config.id || `tunnel-${Date.now()}`,
      name: config.name || `Tunnel ${Date.now()}`,
      type: "tunnel",
      config,
      createdAt: Date.now(),
      lastUsed: 0,
      isFavorite: false,
      tags: [],
    };
    
    this.profiles.set(profile.id, profile);
    this.saveProfiles();
    return profile;
  }
  
  // Get all profiles
  getProfiles(): ProfileConfig[] {
    return Array.from(this.profiles.values()).sort((a, b) => b.lastUsed - a.lastUsed);
  }
  
  // Get profile by ID
  getProfile(id: string): ProfileConfig | undefined {
    return this.profiles.get(id);
  }
  
  // Update profile
  updateProfile(id: string, updates: Partial<ProfileConfig>): ProfileConfig | undefined {
    const profile = this.profiles.get(id);
    if (!profile) return undefined;
    
    const updated = { ...profile, ...updates };
    this.profiles.set(id, updated);
    this.saveProfiles();
    return updated;
  }
  
  // Delete profile
  deleteProfile(id: string): boolean {
    const deleted = this.profiles.delete(id);
    if (deleted) {
      this.saveProfiles();
    }
    return deleted;
  }
  
  // Mark as used
  markAsUsed(id: string): void {
    const profile = this.profiles.get(id);
    if (profile) {
      profile.lastUsed = Date.now();
      this.profiles.set(id, profile);
      this.saveProfiles();
    }
  }
  
  // Toggle favorite
  toggleFavorite(id: string): boolean {
    const profile = this.profiles.get(id);
    if (profile) {
      profile.isFavorite = !profile.isFavorite;
      this.profiles.set(id, profile);
      this.saveProfiles();
      return profile.isFavorite;
    }
    return false;
  }
  
  // Search profiles
  search(query: string): ProfileConfig[] {
    const lowerQuery = query.toLowerCase();
    return Array.from(this.profiles.values())
      .filter((profile) => 
        profile.name.toLowerCase().includes(lowerQuery) ||
        profile.tags.some((tag) => tag.toLowerCase().includes(lowerQuery))
      )
      .sort((a, b) => b.lastUsed - a.lastUsed);
  }
  
  // Get favorites
  getFavorites(): ProfileConfig[] {
    return Array.from(this.profiles.values())
      .filter((profile) => profile.isFavorite)
      .sort((a, b) => b.lastUsed - a.lastUsed);
  }
  
  // Get by type
  getByType(type: ProfileConfig["type"]): ProfileConfig[] {
    return Array.from(this.profiles.values())
      .filter((profile) => profile.type === type)
      .sort((a, b) => b.lastUsed - a.lastUsed);
  }
  
  // Import profile
  importProfile(profile: ProfileConfig): ProfileConfig {
    const id = profile.id || `import-${Date.now()}`;
    const newProfile: ProfileConfig = {
      ...profile,
      id,
      createdAt: Date.now(),
      lastUsed: 0,
    };
    
    this.profiles.set(id, newProfile);
    this.saveProfiles();
    return newProfile;
  }
  
  // Export profile
  exportProfile(id: string): ProfileConfig | undefined {
    const profile = this.profiles.get(id);
    if (!profile) return undefined;
    return { ...profile };
  }
  
  // Export all profiles
  exportAll(): ProfileConfig[] {
    return Array.from(this.profiles.values());
  }
  
  // Clear all profiles
  clearAll(): void {
    this.profiles.clear();
    this.saveProfiles();
  }
}

export const profileConfigManager = new ProfileConfigManager();

// ============================================================================
// Connection History Manager
// ============================================================================

export class ConnectionHistoryManager {
  private storage: LocalStorageWrapper;
  private history: ConnectionHistory[] = [];
  private maxHistory: number = 100;
  
  constructor() {
    this.storage = new LocalStorageWrapper("history-");
    this.loadHistory();
  }
  
  private loadHistory(): void {
    try {
      const saved = this.storage.get<ConnectionHistory[]>("all");
      if (saved) {
        this.history = saved.sort((a, b) => b.connectedAt - a.connectedAt);
      }
    } catch {
      // Ignore errors
    }
  }
  
  private saveHistory(): void {
    try {
      this.storage.set("all", this.history);
    } catch {
      // Ignore errors
    }
  }
  
  // Record a connection
  recordConnection(history: Omit<ConnectionHistory, "id" | "disconnectedAt" | "duration">): ConnectionHistory {
    const newHistory: ConnectionHistory = {
      ...history,
      id: `hist-${Date.now()}`,
      disconnectedAt: 0,
      duration: 0,
    };
    
    this.history.unshift(newHistory);
    
    // Trim history if too large
    if (this.history.length > this.maxHistory) {
      this.history = this.history.slice(0, this.maxHistory);
    }
    
    this.saveHistory();
    return newHistory;
  }
  
  // Update disconnection
  updateDisconnection(id: string): ConnectionHistory | undefined {
    const history = this.history.find((h) => h.id === id);
    if (!history) return undefined;
    
    history.disconnectedAt = Date.now();
    history.duration = history.disconnectedAt - history.connectedAt;
    this.saveHistory();
    return history;
  }
  
  // Get all history
  getHistory(): ConnectionHistory[] {
    return [...this.history];
  }
  
  // Get history for a profile
  getHistoryForProfile(profileId: string): ConnectionHistory[] {
    return this.history.filter((h) => h.profileId === profileId);
  }
  
  // Get recent history
  getRecent(limit: number = 10): ConnectionHistory[] {
    return this.history.slice(0, limit);
  }
  
  // Get statistics
  getStats(): {
    totalConnections: number;
    successfulConnections: number;
    failedConnections: number;
    totalDuration: number;
    totalBytesSent: number;
    totalBytesReceived: number;
    averageDuration: number;
  } {
    const total = this.history.length;
    const successful = this.history.filter((h) => h.success).length;
    const failed = total - successful;
    const totalDuration = this.history.reduce((sum, h) => sum + h.duration, 0);
    const totalBytesSent = this.history.reduce((sum, h) => sum + h.bytesSent, 0);
    const totalBytesReceived = this.history.reduce((sum, h) => sum + h.bytesReceived, 0);
    
    return {
      totalConnections: total,
      successfulConnections: successful,
      failedConnections: failed,
      totalDuration,
      totalBytesSent,
      totalBytesReceived,
      averageDuration: total > 0 ? totalDuration / total : 0,
    };
  }
  
  // Clear history
  clearHistory(): void {
    this.history = [];
    this.saveHistory();
  }
}

export const connectionHistoryManager = new ConnectionHistoryManager();

// ============================================================================
// Secure Credential Storage
// ============================================================================

export type StoredCredential = {
  id: string;
  name: string;
  type: "password" | "key" | "certificate" | "token";
  value: string; // Encrypted in production
  createdAt: number;
  lastUsed: number;
};

export class CredentialManager {
  private storage: LocalStorageWrapper;
  private credentials: Map<string, StoredCredential> = new Map();
  
  constructor() {
    this.storage = new LocalStorageWrapper("cred-");
    this.loadCredentials();
  }
  
  private loadCredentials(): void {
    try {
      const saved = this.storage.get<StoredCredential[]>("all");
      if (saved) {
        for (const cred of saved) {
          this.credentials.set(cred.id, cred);
        }
      }
    } catch {
      // Ignore errors
    }
  }
  
  private saveCredentials(): void {
    try {
      const creds = Array.from(this.credentials.values());
      this.storage.set("all", creds);
    } catch {
      // Ignore errors
    }
  }
  
  // Store a credential
  storeCredential(credential: Omit<StoredCredential, "id" | "createdAt" | "lastUsed">): StoredCredential {
    const id = `cred-${Date.now()}`;
    const newCredential: StoredCredential = {
      ...credential,
      id,
      createdAt: Date.now(),
      lastUsed: 0,
    };
    
    this.credentials.set(id, newCredential);
    this.saveCredentials();
    return newCredential;
  }
  
  // Get credential by ID
  getCredential(id: string): StoredCredential | undefined {
    return this.credentials.get(id);
  }
  
  // Get all credentials
  getAllCredentials(): StoredCredential[] {
    return Array.from(this.credentials.values()).sort((a, b) => b.lastUsed - a.lastUsed);
  }
  
  // Update credential
  updateCredential(id: string, updates: Partial<StoredCredential>): StoredCredential | undefined {
    const cred = this.credentials.get(id);
    if (!cred) return undefined;
    
    const updated = { ...cred, ...updates };
    this.credentials.set(id, updated);
    this.saveCredentials();
    return updated;
  }
  
  // Delete credential
  deleteCredential(id: string): boolean {
    const deleted = this.credentials.delete(id);
    if (deleted) {
      this.saveCredentials();
    }
    return deleted;
  }
  
  // Mark as used
  markAsUsed(id: string): void {
    const cred = this.credentials.get(id);
    if (cred) {
      cred.lastUsed = Date.now();
      this.credentials.set(id, cred);
      this.saveCredentials();
    }
  }
  
  // Clear all credentials
  clearAll(): void {
    this.credentials.clear();
    this.saveCredentials();
  }
  
  // Note: In production, credentials should be encrypted
  // This implementation stores them in plain text for demonstration
  // For real applications, use proper encryption
}

export const credentialManager = new CredentialManager();

// ============================================================================
// Export Summary
// ============================================================================

export {
  LocalStorageWrapper,
  DEFAULT_APP_CONFIG,
  appConfigManager,
  profileConfigManager,
  connectionHistoryManager,
  credentialManager,
};
