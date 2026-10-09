<template>
  <div class="pc-storage">
    <el-form label-position="top" size="small" class="pc-storage-form">
      <el-form-item required>
        <template #label>
          <FieldLabel label="存储类型（storageType）">
            仅可选择本部署已接入的存储实现（{{ supportedTypes.join(' / ') || '未知' }}），
            由服务端 <code>paimon.rest.file-io.type</code> 决定。
            <template v-if="unknownType">
              当前值 <code>{{ unknownType }}</code> 不在本控制台的类型表里，
              保存时会原样带回，不会被改写。
            </template>
          </FieldLabel>
        </template>
        <el-select v-model="form.storageType" class="pc-full" @change="onTypeChange">
          <el-option
            v-if="unknownType"
            :label="`${unknownType}（服务端取值，控制台未识别）`"
            :value="unknownType"
          />
          <el-option
            v-for="type in storageTypes"
            :key="type"
            :label="type"
            :value="type"
            :disabled="!supportedTypes.includes(type)"
          />
        </el-select>
      </el-form-item>

      <el-form-item required>
        <template #label>
          <FieldLabel
            label="允许位置（allowedLocations）"
            tip="首项同时作为仓库根（warehouse）；白名单之外的位置服务端会拒绝写入。"
          />
        </template>
        <div class="pc-list">
          <div v-for="(_, index) in form.allowedLocations" :key="index" class="pc-list-row">
            <el-input
              v-model="form.allowedLocations[index]"
              size="small"
              :placeholder="locationPlaceholder"
            />
            <el-button size="small" text type="danger" @click="removeLocation(index)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
          <el-button size="small" text type="primary" @click="form.allowedLocations.push('')">
            <el-icon><Plus /></el-icon>
            <span class="pc-list-add">添加位置</span>
          </el-button>
        </div>
      </el-form-item>

      <el-form-item>
        <template #label>
          <FieldLabel label="具名存储（storageName）">
            填服务端 <code>paimon.rest.storage.&lt;厂商&gt;.storages</code> 下的键名。
            这种做法让密钥只留在服务端配置里，不出现在 catalog 元数据中。
          </FieldLabel>
        </template>
        <el-input v-model="form.storageName" placeholder="留空则使用服务端默认凭据" />
      </el-form-item>

      <template v-for="field in fields" :key="field.key">
        <el-form-item>
          <template #label>
            <FieldLabel :label="field.label" :tip="field.hint" />
          </template>
          <el-switch v-if="field.type === 'bool'" v-model="form.extras[field.key]" />
          <div v-else-if="field.type === 'list'" class="pc-list">
            <div v-for="(_, index) in form.extras[field.key]" :key="index" class="pc-list-row">
              <el-input v-model="form.extras[field.key][index]" size="small" :placeholder="field.placeholder || ''" />
              <el-button size="small" text type="danger" @click="form.extras[field.key].splice(index, 1)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <el-button size="small" text type="primary" @click="form.extras[field.key].push('')">
              <el-icon><Plus /></el-icon>
              <span class="pc-list-add">添加一项</span>
            </el-button>
          </div>
          <el-input v-else v-model="form.extras[field.key]" :placeholder="field.placeholder || ''" />
        </el-form-item>
      </template>

      <el-alert
        v-if="carriedKeys.length"
        type="info"
        :closable="false"
        show-icon
        class="pc-storage-carry"
      >
        <template #title>以下字段没有对应控件，保存时原样带回服务端</template>
        <div class="pc-mono pc-break">{{ carriedKeys.join('、') }}</div>
      </el-alert>
    </el-form>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'

import FieldLabel from '@/components/FieldLabel.vue'

/**
 * `StorageConfigInfo` 的表单。
 *
 * <p>按 `storageType` 动态出字段，而不是把六种存储的字段全列出来：
 * 服务端把 `StorageConfigInfo` 建模成判别联合，每种类型只接受自己的字段
 * （见 `StorageDtos`），表单跟着这个结构走，才能保证提交上去的组合是合法的。
 * 切换类型时构造新对象而不是就地删字段，避免残留上一个类型的属性。
 *
 * <p><b>两条不能违反的约定</b>（违反的后果是静默改坏配置，而不是报错）：
 *
 * <ol>
 *   <li><b>外部传入的 `modelValue` 必须能进入表单。</b>组件内部维护一份可编辑副本，
 *       如果只在创建时读一次 `props`，那么第二次打开对话框（编辑）时表单停留
 *       在上一次的值上，保存就会把 A 类型改写成 B 类型。这里用
 *       {@link watch} 同步，并用序列化快照区分「外部改了」与「自己刚发出去的」
 *       ——与 `PropertiesEditor` 用同一套办法。
 *   <li><b>不认识的字段必须原样带回。</b>服务端将来给某个存储类型加字段时，
 *       控制台的表单没有对应控件；如果 `build()` 只输出自己的已知字段，
 *       一次「只改了个名字」的保存会把这些字段抹掉。因此这里记住原始对象里
 *       表单覆盖不到的部分并一起提交。
 * </ol>
 */

/** 所有子类型共有的字段。 */
const COMMON_KEYS = ['storageType', 'allowedLocations', 'storageName']

/**
 * 各存储类型的专属字段。`bool` 用开关，其余用输入框。
 *
 * <p>OBS / OSS 只有 `endpoint` 与 `stsUnavailable`：这是本工程的扩展类型，
 * 字段刻意保持最小（服务端 `StorageDtos` 的说明里写了为什么不给它们 `region`
 * ——两家云的 FileIO 配置面都没有这一项，区域含在 endpoint 里）。
 */
const TYPE_FIELDS = {
  S3: [
    { key: 'region', label: 'region', placeholder: 'us-east-1' },
    { key: 'endpoint', label: 'endpoint', placeholder: 'https://s3.us-east-1.amazonaws.com' },
    { key: 'stsEndpoint', label: 'stsEndpoint', placeholder: '可选，STS 端点' },
    { key: 'endpointInternal', label: 'endpointInternal', placeholder: '可选，内网端点' },
    { key: 'roleArn', label: 'roleArn', placeholder: '跨账户访问时填写' },
    { key: 'externalId', label: 'externalId' },
    { key: 'userArn', label: 'userArn' },
    { key: 'pathStyleAccess', label: 'pathStyleAccess', type: 'bool' },
    { key: 'stsUnavailable', label: 'stsUnavailable', type: 'bool' },
    { key: 'kmsUnavailable', label: 'kmsUnavailable', type: 'bool' },
    { key: 'encryptionKeys', label: 'encryptionKeys', type: 'list', placeholder: 'KMS 密钥 ARN' },
    { key: 'decryptionKeys', label: 'decryptionKeys', type: 'list', placeholder: 'KMS 密钥 ARN' },
  ],
  GCS: [{ key: 'gcsServiceAccount', label: 'gcsServiceAccount', placeholder: '服务账号邮箱' }],
  AZURE: [
    { key: 'tenantId', label: 'tenantId', placeholder: 'Azure 租户 ID（必填）' },
    { key: 'multiTenantAppName', label: 'multiTenantAppName' },
    { key: 'consentUrl', label: 'consentUrl' },
    { key: 'hierarchical', label: 'hierarchical', type: 'bool' },
  ],
  OBS: [
    { key: 'endpoint', label: 'endpoint', placeholder: 'obs.cn-north-4.myhuaweicloud.com' },
    { key: 'stsUnavailable', label: 'stsUnavailable', type: 'bool' },
  ],
  OSS: [
    { key: 'endpoint', label: 'endpoint', placeholder: 'oss-cn-hangzhou.aliyuncs.com' },
    { key: 'stsUnavailable', label: 'stsUnavailable', type: 'bool' },
  ],
  FILE: [],
}

/**
 * 各类型允许的位置前缀。
 *
 * <p>用于拦「类型选 S3、位置填 file:///...」这类组合：两者各自都合法，
 * 拼在一起服务端只有到真正读写时才会失败，而那时错误已经离操作现场很远了。
 */
const LOCATION_SCHEMES = {
  S3: ['s3://', 's3a://'],
  GCS: ['gs://'],
  AZURE: ['abfss://', 'abfs://', 'wasbs://'],
  OBS: ['obs://'],
  OSS: ['oss://'],
  FILE: ['file://', 'file:/', '/'],
}

const props = defineProps({
  modelValue: { type: Object, default: () => ({}) },
  /** 全部存储类型取值（服务端元数据） */
  storageTypes: { type: Array, default: () => ['S3', 'GCS', 'AZURE', 'OBS', 'OSS', 'FILE'] },
  /** 本部署实际支持的存储类型 */
  supportedTypes: { type: Array, default: () => [] },
})

const emit = defineEmits(['update:modelValue'])

/** 表单不认识、但要原样带回服务端的字段。 */
const carried = ref({})

const form = reactive({
  storageType: '',
  allowedLocations: [],
  storageName: '',
  extras: {},
})

/** 表单覆盖的键集合（按类型不同）。 */
function knownKeys(type) {
  return new Set([...COMMON_KEYS, ...(TYPE_FIELDS[type] || []).map((field) => field.key)])
}

/** 服务端返回的类型不在本控制台的类型表里时，保留原值并提示。 */
const unknownType = computed(() => {
  const type = form.storageType
  if (!type) return ''
  return TYPE_FIELDS[type] ? '' : type
})

function collectExtras(source, type) {
  const extras = {}
  for (const field of TYPE_FIELDS[type] || []) {
    const value = source[field.key]
    if (field.type === 'bool') {
      extras[field.key] = Boolean(value)
    } else if (field.type === 'list') {
      extras[field.key] = Array.isArray(value) ? value.map((item) => String(item ?? '')) : []
    } else {
      extras[field.key] = value ?? ''
    }
  }
  return extras
}

function splitCarried(source, type) {
  const known = knownKeys(type)
  const rest = {}
  for (const [key, value] of Object.entries(source || {})) {
    if (!known.has(key)) {
      rest[key] = value
    }
  }
  return rest
}

function defaultType() {
  return props.supportedTypes[0] || 'FILE'
}

/**
 * 把外部对象灌进表单。
 *
 * <p>类型只认「外部给了什么」；没给才退回本部署支持的第一个类型。
 * 这里不做「不在 supportedTypes 里就纠正」的处理：那会把一份服务端认可、
 * 只是本控制台不支持编辑的配置改坏。
 */
function adopt(source) {
  const next = source || {}
  const type = next.storageType || defaultType()
  form.storageType = type
  form.allowedLocations = [...(next.allowedLocations || [])]
  form.storageName = next.storageName || ''
  form.extras = collectExtras(next, type)
  carried.value = splitCarried(next, type)
}

const fields = computed(() => TYPE_FIELDS[form.storageType] || [])

const carriedKeys = computed(() => Object.keys(carried.value))

const locationPlaceholder = computed(() => ({
  S3: 's3://bucket/prefix',
  GCS: 'gs://bucket/prefix',
  AZURE: 'abfss://container@account.dfs.core.windows.net/path',
  OBS: 'obs://bucket/prefix',
  OSS: 'oss://bucket/prefix',
  FILE: 'file:///data/warehouse',
}[form.storageType] || '存储位置 URI'))

/** 切换类型：只保留该类型的字段，避免把上一个类型的属性带到新配置里。 */
function onTypeChange(next) {
  form.extras = collectExtras({}, next)
  carried.value = splitCarried(carried.value, next)
  push()
}

function removeLocation(index) {
  form.allowedLocations.splice(index, 1)
  push()
}

/** 收敛成服务端要的形状：丢掉空串与空数组，布尔值原样带上。 */
function build() {
  // 先铺开表单覆盖不到的字段，再覆盖已知键——顺序不能反，否则已知键会被旧值盖回
  const config = { ...carried.value }
  config.storageType = form.storageType
  config.allowedLocations = form.allowedLocations.map((item) => (item || '').trim()).filter(Boolean)
  const storageName = (form.storageName || '').trim()
  if (storageName) {
    config.storageName = storageName
  } else {
    delete config.storageName
  }
  for (const field of fields.value) {
    const value = form.extras[field.key]
    if (field.type === 'bool') {
      config[field.key] = Boolean(value)
    } else if (field.type === 'list') {
      const items = (value || []).map((item) => String(item ?? '').trim()).filter(Boolean)
      if (items.length) {
        config[field.key] = items
      } else {
        delete config[field.key]
      }
    } else if (value !== undefined && value !== null && String(value).trim() !== '') {
      config[field.key] = String(value).trim()
    } else {
      delete config[field.key]
    }
  }
  return config
}

function snapshot(value) {
  return JSON.stringify(value ?? {})
}

/** 最近一次由本组件发出的值，用来区分「外部改了」与「回声」。 */
let lastEmitted = ''

function push() {
  const built = build()
  const serialized = snapshot(built)
  if (serialized === lastEmitted) {
    return
  }
  lastEmitted = serialized
  emit('update:modelValue', built)
}

adopt(props.modelValue)
lastEmitted = snapshot(build())

watch(
  () => props.modelValue,
  (next) => {
    // 值与我们刚发出去的一致，说明这次变化是自己的回声，灌回去只会让光标跳位
    if (snapshot(next) === lastEmitted) {
      return
    }
    adopt(next)
    lastEmitted = snapshot(build())
  },
)

watch(form, push, { deep: true })

/** 由父组件在提交前调用；返回第一条不合规的提示，全部通过时返回空串。 */
function validate() {
  if (!form.storageType) return '请选择存储类型'
  const locations = build().allowedLocations
  if (!locations.length) return '至少填写一个允许位置（allowedLocations）'
  const schemes = LOCATION_SCHEMES[form.storageType]
  if (schemes) {
    const bad = locations.find((item) => !schemes.some((scheme) => item.startsWith(scheme)))
    if (bad) {
      return `位置「${bad}」不符合 ${form.storageType} 的写法（应以 ${schemes.join(' 或 ')} 开头）`
    }
  }
  if (form.storageType === 'AZURE' && !String(form.extras.tenantId || '').trim()) {
    return 'Azure 存储必须填写 tenantId'
  }
  return ''
}

defineExpose({ validate, build })
</script>

<style scoped>

.pc-list-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  width: 100%;
}

.pc-list-add {
  margin-left: 4px;
}

.pc-storage-carry {
  margin-top: 4px;
}

.pc-storage-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.pc-storage-form :deep(.el-form-item__label) {
  padding-bottom: 2px;
  font-size: 12.5px;
  color: var(--pc-text-dim);
  line-height: 1.4;
}
</style>
