use std::collections::{BTreeMap, HashSet, VecDeque};
use std::ffi::c_void;

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use serde_json::json;

const MAX_BACKUP_ITEMS_PER_SECTION: usize = 20_000;
const MAX_BACKUP_ITEM_UTF16_UNITS: usize = 8 * 1024 * 1024;
const MAX_BACKUP_KEY_UTF16_UNITS: usize = 256;

const GROUP_TYPE_BASIC: i32 = 0;
const GROUP_TYPE_SUBSCRIPTION: i32 = 1;
const GROUP_ORDER_ORIGIN: i32 = 0;
const GROUP_ORDER_BY_DELAY: i32 = 2;

const VALUE_TYPE_BOOLEAN: i32 = 1;
const VALUE_TYPE_FLOAT: i32 = 2;
const VALUE_TYPE_INT: i32 = 3;
const VALUE_TYPE_LONG: i32 = 4;
const VALUE_TYPE_STRING: i32 = 5;
const VALUE_TYPE_STRING_SET: i32 = 6;

const KEY_PROFILE_GROUP: &str = "profileGroup";
const KEY_PROFILE_ID: &str = "profileId";
const KEY_PROFILE_CURRENT: &str = "profileCurrent";

#[derive(Debug, Deserialize)]
#[serde(tag = "operation", rename_all = "snake_case")]
enum BackupRequest {
    ValidateEncodedSection {
        name: String,
        values: Vec<String>,
    },
    ValidateDecodedData {
        #[serde(default)]
        profiles: Option<Vec<BackupProfile>>,
        #[serde(default)]
        groups: Option<Vec<BackupGroup>>,
        #[serde(default)]
        rules: Option<Vec<BackupRule>>,
        #[serde(default)]
        settings: Option<Vec<BackupSetting>>,
        #[serde(default)]
        existing_profile_ids: Option<Vec<i64>>,
        #[serde(default)]
        existing_group_ids: Option<Vec<i64>>,
    },
    ReconcileSelections {
        settings: Vec<SelectionSetting>,
        profile_ids: Vec<i64>,
        group_ids: Vec<i64>,
        fallback_group_id: i64,
    },
}

#[derive(Debug, Deserialize)]
struct BackupProfile {
    id: i64,
    group_id: i64,
    user_order: i64,
}

#[derive(Debug, Deserialize)]
struct BackupGroup {
    id: i64,
    user_order: i64,
    group_type: i32,
    order: i32,
    subscription_present: bool,
    front_proxy: i64,
    landing_proxy: i64,
}

#[derive(Debug, Deserialize)]
struct BackupRule {
    id: i64,
    user_order: i64,
    outbound: i64,
}

#[derive(Debug, Deserialize)]
struct BackupSetting {
    key: String,
    value_type: i32,
    value_size: usize,
    string_set_valid: bool,
    #[serde(default)]
    long_value: Option<i64>,
}

#[derive(Debug, Deserialize)]
struct SelectionSetting {
    key: String,
    #[serde(default)]
    long_value: Option<i64>,
}

#[derive(Debug, Serialize)]
struct SelectionReplacement {
    key: String,
    value: i64,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "operation", rename_all = "snake_case")]
enum SubscriptionRequest {
    Plan {
        incoming: Vec<SubscriptionIncoming>,
        existing: Vec<SubscriptionExisting>,
    },
    SelectionFallback {
        selected_present: bool,
    },
}

#[derive(Debug, Deserialize)]
struct SubscriptionIncoming {
    name: String,
    #[serde(default)]
    equal_existing_ids: Vec<i64>,
}

#[derive(Debug, Deserialize)]
struct SubscriptionExisting {
    id: i64,
    name: String,
    user_order: i64,
}

#[derive(Debug, Serialize)]
struct SubscriptionAction {
    incoming_index: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    existing_id: Option<i64>,
    action: SubscriptionActionKind,
    user_order: i64,
}

#[derive(Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum SubscriptionActionKind {
    Add,
    Update,
    Reorder,
    Unchanged,
}

fn utf16_len(value: &str) -> usize {
    value.encode_utf16().count()
}

fn validate_encoded_section(name: &str, values: &[String]) -> Result<(), String> {
    if values.len() > MAX_BACKUP_ITEMS_PER_SECTION {
        return Err(format!("{name} contains too many items"));
    }
    for value in values {
        if utf16_len(value) > MAX_BACKUP_ITEM_UTF16_UNITS {
            return Err(format!("{name} item is too large"));
        }
        if !value.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-' | b'+' | b'/' | b'=')
        }) {
            return Err(format!("{name} contains invalid base64 data"));
        }
    }
    Ok(())
}

fn unique_ids(ids: impl IntoIterator<Item = i64>, message: &str) -> Result<HashSet<i64>, String> {
    let mut result = HashSet::new();
    for id in ids {
        if !result.insert(id) {
            return Err(message.to_owned());
        }
    }
    Ok(result)
}

fn validate_decoded_data(
    profiles: Option<&[BackupProfile]>,
    groups: Option<&[BackupGroup]>,
    rules: Option<&[BackupRule]>,
    settings: Option<&[BackupSetting]>,
    existing_profile_ids: Option<&[i64]>,
    existing_group_ids: Option<&[i64]>,
) -> Result<(), String> {
    if profiles.is_some() != groups.is_some() {
        return Err("Profiles and groups must be imported together".to_owned());
    }

    let profile_ids = if let Some(profiles) = profiles {
        let mut ids = HashSet::new();
        for profile in profiles {
            if profile.id <= 0 || profile.group_id <= 0 || profile.user_order < 0 {
                return Err("Profile contains invalid identifiers".to_owned());
            }
            if !ids.insert(profile.id) {
                return Err("Profiles contain duplicate IDs".to_owned());
            }
        }
        ids
    } else {
        HashSet::new()
    };

    let available_profile_ids = if profiles.is_some() {
        Some(profile_ids.clone())
    } else {
        existing_profile_ids.map(|ids| ids.iter().copied().collect())
    };

    let available_group_ids = if let Some(groups) = groups {
        if !groups.is_empty()
            && !groups
                .iter()
                .any(|group| group.group_type == GROUP_TYPE_BASIC)
        {
            return Err("Groups must contain a basic import target".to_owned());
        }
        let mut group_ids = HashSet::new();
        for group in groups {
            if group.id <= 0 || group.user_order < 0 {
                return Err("Group contains invalid identifiers".to_owned());
            }
            if group.group_type != GROUP_TYPE_BASIC && group.group_type != GROUP_TYPE_SUBSCRIPTION {
                return Err("Group contains an unsupported type".to_owned());
            }
            if !(GROUP_ORDER_ORIGIN..=GROUP_ORDER_BY_DELAY).contains(&group.order) {
                return Err("Group contains an unsupported order".to_owned());
            }
            if group.group_type == GROUP_TYPE_SUBSCRIPTION && !group.subscription_present {
                return Err("Subscription group is missing subscription data".to_owned());
            }
            for reference in [group.front_proxy, group.landing_proxy] {
                if reference > 0 && !profile_ids.contains(&reference) {
                    return Err("Group refers to a missing profile".to_owned());
                }
            }
            if !group_ids.insert(group.id) {
                return Err("Groups contain duplicate IDs".to_owned());
            }
        }
        if profiles
            .unwrap_or_default()
            .iter()
            .any(|profile| !group_ids.contains(&profile.group_id))
        {
            return Err("Profile refers to a missing group".to_owned());
        }
        Some(group_ids)
    } else {
        existing_group_ids.map(|ids| ids.iter().copied().collect())
    };

    if let Some(rules) = rules {
        let rule_ids = unique_ids(
            rules.iter().map(|rule| rule.id),
            "Rules contain duplicate IDs",
        )?;
        if rules.iter().any(|rule| rule.id <= 0 || rule.user_order < 0) {
            return Err("Rule contains invalid identifiers".to_owned());
        }
        debug_assert_eq!(rule_ids.len(), rules.len());
        if let Some(available_profile_ids) = &available_profile_ids {
            if rules
                .iter()
                .any(|rule| rule.outbound > 0 && !available_profile_ids.contains(&rule.outbound))
            {
                return Err("Rule refers to a missing profile".to_owned());
            }
        }
    }

    if let Some(settings) = settings {
        let mut keys = HashSet::new();
        for setting in settings {
            if !keys.insert(&setting.key) {
                return Err("Settings contain duplicate keys".to_owned());
            }
            if setting.key.trim().is_empty() || utf16_len(&setting.key) > MAX_BACKUP_KEY_UTF16_UNITS
            {
                return Err("Setting contains an invalid key".to_owned());
            }
            if setting.value_size > MAX_BACKUP_ITEM_UTF16_UNITS {
                return Err("Setting value is too large".to_owned());
            }
            let valid_value = match setting.value_type {
                VALUE_TYPE_BOOLEAN => setting.value_size == 1,
                VALUE_TYPE_FLOAT => setting.value_size == 4,
                VALUE_TYPE_INT => setting.value_size == 4,
                VALUE_TYPE_LONG => setting.value_size == 8,
                VALUE_TYPE_STRING => true,
                VALUE_TYPE_STRING_SET => setting.string_set_valid,
                _ => false,
            };
            if !valid_value {
                return Err("Setting contains an invalid value".to_owned());
            }
        }
        if let Some(available_group_ids) = &available_group_ids {
            if settings.iter().any(|setting| {
                setting.key == KEY_PROFILE_GROUP
                    && setting
                        .long_value
                        .is_some_and(|id| id > 0 && !available_group_ids.contains(&id))
            }) {
                return Err("Settings refer to a missing group".to_owned());
            }
        }
        if let Some(available_profile_ids) = &available_profile_ids {
            if settings.iter().any(|setting| {
                (setting.key == KEY_PROFILE_ID || setting.key == KEY_PROFILE_CURRENT)
                    && setting
                        .long_value
                        .is_some_and(|id| id > 0 && !available_profile_ids.contains(&id))
            }) {
                return Err("Settings refer to a missing profile".to_owned());
            }
        }
    }

    Ok(())
}

fn reconcile_selections(
    settings: &[SelectionSetting],
    profile_ids: &HashSet<i64>,
    group_ids: &HashSet<i64>,
    fallback_group_id: i64,
) -> Vec<SelectionReplacement> {
    settings
        .iter()
        .filter_map(|setting| match setting.key.as_str() {
            KEY_PROFILE_GROUP
                if setting
                    .long_value
                    .is_some_and(|id| id > 0 && !group_ids.contains(&id)) =>
            {
                Some(SelectionReplacement {
                    key: setting.key.clone(),
                    value: fallback_group_id,
                })
            }
            KEY_PROFILE_ID | KEY_PROFILE_CURRENT
                if setting
                    .long_value
                    .is_some_and(|id| id > 0 && !profile_ids.contains(&id)) =>
            {
                Some(SelectionReplacement {
                    key: setting.key.clone(),
                    value: 0,
                })
            }
            _ => None,
        })
        .collect()
}

fn plan_subscription_update(
    incoming: &[SubscriptionIncoming],
    existing: Vec<SubscriptionExisting>,
) -> (Vec<SubscriptionAction>, Vec<i64>) {
    let mut existing_by_name: BTreeMap<String, Vec<(usize, SubscriptionExisting)>> =
        BTreeMap::new();
    for (index, profile) in existing.into_iter().enumerate() {
        existing_by_name
            .entry(profile.name.clone())
            .or_default()
            .push((index, profile));
    }
    let mut queues: BTreeMap<String, VecDeque<SubscriptionExisting>> = existing_by_name
        .into_iter()
        .map(|(name, mut values)| {
            values.sort_by_key(|(index, profile)| (profile.user_order, *index));
            (
                name,
                values
                    .into_iter()
                    .map(|(_, profile)| profile)
                    .collect::<VecDeque<_>>(),
            )
        })
        .collect();

    let actions = incoming
        .iter()
        .enumerate()
        .map(|(index, profile)| {
            let user_order = index as i64 + 1;
            if let Some(existing) = queues.get_mut(&profile.name).and_then(VecDeque::pop_front) {
                let action = if profile.equal_existing_ids.contains(&existing.id) {
                    if existing.user_order == user_order {
                        SubscriptionActionKind::Unchanged
                    } else {
                        SubscriptionActionKind::Reorder
                    }
                } else {
                    SubscriptionActionKind::Update
                };
                SubscriptionAction {
                    incoming_index: index,
                    existing_id: Some(existing.id),
                    action,
                    user_order,
                }
            } else {
                SubscriptionAction {
                    incoming_index: index,
                    existing_id: None,
                    action: SubscriptionActionKind::Add,
                    user_order,
                }
            }
        })
        .collect();

    let deletions = queues
        .into_values()
        .flatten()
        .map(|profile| profile.id)
        .collect();
    (actions, deletions)
}

fn backup_response(request: &str) -> String {
    let result = serde_json::from_str::<BackupRequest>(request)
        .map_err(|error| error.to_string())
        .and_then(|request| match request {
            BackupRequest::ValidateEncodedSection { name, values } => {
                validate_encoded_section(&name, &values).map(|()| json!({}))
            }
            BackupRequest::ValidateDecodedData {
                profiles,
                groups,
                rules,
                settings,
                existing_profile_ids,
                existing_group_ids,
            } => validate_decoded_data(
                profiles.as_deref(),
                groups.as_deref(),
                rules.as_deref(),
                settings.as_deref(),
                existing_profile_ids.as_deref(),
                existing_group_ids.as_deref(),
            )
            .map(|()| json!({})),
            BackupRequest::ReconcileSelections {
                settings,
                profile_ids,
                group_ids,
                fallback_group_id,
            } => Ok(json!({
                "replacements": reconcile_selections(
                    &settings,
                    &profile_ids.into_iter().collect(),
                    &group_ids.into_iter().collect(),
                    fallback_group_id,
                ),
            })),
        });
    match result {
        Ok(data) => json!({ "ok": true, "data": data }).to_string(),
        Err(error) => json!({ "ok": false, "error": error }).to_string(),
    }
}

fn subscription_response(request: &str) -> String {
    let result = serde_json::from_str::<SubscriptionRequest>(request)
        .map_err(|error| error.to_string())
        .map(|request| match request {
            SubscriptionRequest::Plan { incoming, existing } => {
                let (actions, deletion_ids) = plan_subscription_update(&incoming, existing);
                json!({ "actions": actions, "deletion_ids": deletion_ids })
            }
            SubscriptionRequest::SelectionFallback { selected_present } => {
                json!({ "required": !selected_present })
            }
        });
    match result {
        Ok(data) => json!({ "ok": true, "data": data }).to_string(),
        Err(error) => json!({ "ok": false, "error": error }).to_string(),
    }
}

fn jni_response(
    mut env: JNIEnv,
    request: JString,
    response: impl FnOnce(&str) -> String + std::panic::UnwindSafe,
) -> jstring {
    let request: String = match env.get_string(&request) {
        Ok(request) => request.into(),
        Err(_) => return std::ptr::null_mut::<c_void>() as jstring,
    };
    let result = std::panic::catch_unwind(|| response(&request)).unwrap_or_else(|_| {
        json!({ "ok": false, "error": "Rust data core internal failure" }).to_string()
    });
    env.new_string(result)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut::<c_void>() as jstring)
}

#[no_mangle]
pub extern "system" fn Java_io_nekohasekai_sagernet_core_RustDataCore_validateBackupNative(
    env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    jni_response(env, request, backup_response)
}

#[no_mangle]
pub extern "system" fn Java_io_nekohasekai_sagernet_core_RustDataCore_planSubscriptionUpdateNative(
    env: JNIEnv,
    _class: JClass,
    request: JString,
) -> jstring {
    jni_response(env, request, subscription_response)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_legacy_and_url_safe_base64() {
        validate_encoded_section("profiles", &["YWJjLQ".to_owned(), "YWJj+Q==".to_owned()])
            .unwrap();
    }

    #[test]
    fn rejects_invalid_backup_references() {
        let profiles = vec![BackupProfile {
            id: 1,
            group_id: 1,
            user_order: 1,
        }];
        let groups = vec![BackupGroup {
            id: 1,
            user_order: 1,
            group_type: GROUP_TYPE_BASIC,
            order: GROUP_ORDER_BY_DELAY,
            subscription_present: false,
            front_proxy: -1,
            landing_proxy: -1,
        }];
        let rules = vec![BackupRule {
            id: 1,
            user_order: 1,
            outbound: 2,
        }];
        assert_eq!(
            validate_decoded_data(
                Some(&profiles),
                Some(&groups),
                Some(&rules),
                None,
                None,
                None
            )
            .unwrap_err(),
            "Rule refers to a missing profile"
        );
    }

    #[test]
    fn reconciles_stale_selections() {
        let output = reconcile_selections(
            &[
                SelectionSetting {
                    key: KEY_PROFILE_GROUP.to_owned(),
                    long_value: Some(9),
                },
                SelectionSetting {
                    key: KEY_PROFILE_ID.to_owned(),
                    long_value: Some(8),
                },
            ],
            &HashSet::from([2]),
            &HashSet::from([3]),
            3,
        );
        assert_eq!(output.len(), 2);
        assert_eq!(output[0].value, 3);
        assert_eq!(output[1].value, 0);
    }

    #[test]
    fn subscription_plan_keeps_duplicate_names_in_order() {
        let incoming = vec![
            SubscriptionIncoming {
                name: "same".to_owned(),
                equal_existing_ids: vec![2],
            },
            SubscriptionIncoming {
                name: "same".to_owned(),
                equal_existing_ids: vec![1],
            },
            SubscriptionIncoming {
                name: "new".to_owned(),
                equal_existing_ids: vec![],
            },
        ];
        let existing = vec![
            SubscriptionExisting {
                id: 1,
                name: "same".to_owned(),
                user_order: 2,
            },
            SubscriptionExisting {
                id: 2,
                name: "same".to_owned(),
                user_order: 1,
            },
            SubscriptionExisting {
                id: 3,
                name: "old".to_owned(),
                user_order: 3,
            },
        ];
        let (actions, deletions) = plan_subscription_update(&incoming, existing);
        assert_eq!(actions[0].existing_id, Some(2));
        assert_eq!(actions[0].action, SubscriptionActionKind::Unchanged);
        assert_eq!(actions[1].existing_id, Some(1));
        assert_eq!(actions[1].action, SubscriptionActionKind::Unchanged);
        assert_eq!(actions[2].action, SubscriptionActionKind::Add);
        assert_eq!(deletions, vec![3]);
    }

    #[test]
    fn subscription_plan_marks_content_change_and_reorder() {
        let incoming = vec![
            SubscriptionIncoming {
                name: "one".to_owned(),
                equal_existing_ids: vec![],
            },
            SubscriptionIncoming {
                name: "two".to_owned(),
                equal_existing_ids: vec![2],
            },
        ];
        let existing = vec![
            SubscriptionExisting {
                id: 1,
                name: "one".to_owned(),
                user_order: 1,
            },
            SubscriptionExisting {
                id: 2,
                name: "two".to_owned(),
                user_order: 3,
            },
        ];
        let (actions, _) = plan_subscription_update(&incoming, existing);
        assert_eq!(actions[0].action, SubscriptionActionKind::Update);
        assert_eq!(actions[1].action, SubscriptionActionKind::Reorder);
    }
}
