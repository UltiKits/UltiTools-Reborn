/**
 * Enhanced data entity classes with generic ID types and lifecycle hooks.
 * <p>
 * This package provides improved data entity abstractions:
 * <ul>
 *   <li>{@link com.ultikits.ultitools.abstracts.data.BaseDataEntity} - Generic ID with lifecycle hooks</li>
 *   <li>{@link com.ultikits.ultitools.abstracts.data.AuditableDataEntity} - Adds audit fields (created/updated timestamps)</li>
 * </ul>
 * 
 * <h2>Usage:</h2>
 * <pre>{@code
 * // With String ID (most common for UltiTools plugins)
 * public class PlayerData extends BaseDataEntity<String> {
 *     private String name;
 *
 *     @Override
 *     public void onCreate() {
 *         // Called before first save
 *     }
 * }
 *
 * // With UUID ID
 * public class PlayerData extends BaseDataEntity<UUID> {
 *     private String name;
 * }
 *
 * // With audit fields
 * public class PlayerData extends AuditableDataEntity<UUID> {
 *     private String name;
 *     // Automatically tracks createdAt, updatedAt, createdBy, updatedBy
 * }
 * }</pre>
 * 
 * <h2>Lifecycle Hooks:</h2>
 * <ul>
 *   <li>{@code onCreate()} - Called before first insert</li>
 *   <li>{@code onUpdate()} - Called before update</li>
 *   <li>{@code onDelete()} - Called before delete</li>
 *   <li>{@code onLoad()} - Called after loading from storage</li>
 *   <li>{@code validate()} - Called before any persistence operation</li>
 * </ul>
 *
 * @since 6.2.0
 */
package com.ultikits.ultitools.abstracts.data;
