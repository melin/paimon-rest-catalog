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

      <template v-if="supportsStaticCredentials">
        <el-divider content-position="left">静态凭据（可选）</el-divider>

        <el-form-item>
          <template #label>
            <FieldLabel
              label="accessKeyId"
              tip="该 catalog 访问对象存储用的长期密钥 ID。与具名存储（storageName）互斥：两者都在回答「用哪把钥匙」。"
            />
          </template>
          <el-input
            v-model="form.accessKeyId"
            :disabled="!staticCredentialsEnabled"
            placeholder="例如 minioadmin"
          />
        </el-form-item>

        <el-form-item>
          <template #label>
            <FieldLabel
              label="secretAccessKey"
              tip="只写不读：服务端从不回显该值（库内加密保存）。留空表示保持已保存的密钥不变。"
            />
          </template>
          <el-input
            v-model="form.secretAccessKey"
            type="password"
            show-password
            :disabled="!staticCredentialsEnabled"
            :placeholder="secretPlaceholder"
          />
        </el-form-item>

        <el-form-item v-if="storedAccessKeyId && !form.removeCredentials">
          <el-button size="small" text type="danger" @click="form.removeCredentials = true">
            <el-icon><Delete /></el-icon>
            <span class="pc-list-add">移除已保存的静态凭据</span>
          </el-button>
        </el-form-item>

        <el-alert v-if="form.removeCredentials" type="info" :closable="false" show-icon class="pc-storage-carry">
          <template #title>保存后将移除这个 catalog 的静态凭据</template>
          <div>
            之后该 catalog 会回到「服务端配置的密钥」这条路径（默认凭据 → 环境凭据链）。
            <el-button size="small" text type="primary" @click="form.removeCredentials = false">取消移除</el-button>
          </div>
        </el-alert>
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
 * 静态凭据的字段名，以及哪些存储类型有这对字段。
 *
 * <p>它们是服务端 `StorageConfigInfo` 的扩展字段（规格里没有「用户填 AK/SK」的位置），
 * 语义与普通字段不同：`secretAccessKey` 只写不读，服务端从不回显，
 * 因此这里单独处理，不走下面的 `TYPE_FIELDS` 那套「空值即删除」的通用规则——
 * 对它来说「空」的含义是「保持不变」，见 `build()` 的说明。
 */
const STATIC_CREDENTIAL_KEYS = ['accessKeyId', 'secretAccessKey']

const STATIC_CREDENTIAL_TYPES = ['S3', 'OBS', 'OSS']

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
  /**
   * 本部署能否保存静态凭据（服务端是否配了加密密钥）。
   *
   * <p>默认 false 而不是 true：读不到元数据时（服务端旧版本、或连接未就绪）
   * 乐观地放开输入，只会让用户填完再吃一个服务端 400。
   */
  staticCredentialsEnabled: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue'])

/** 表单不认识、但要原样带回服务端的字段。 */
const carried = ref({})

/** 服务端返回的 accessKeyId，用来判断「已保存了静态凭据」与是否更换了密钥 ID。 */
const storedAccessKeyId = ref('')

const form = reactive({
  storageType: '',
  allowedLocations: [],
  storageName: '',
  extras: {},
  accessKeyId: '',
  secretAccessKey: '',
  /** 显式移除已保存的静态凭据（勾上则提交一对空串，见 build()）。 */
  removeCredentials: false,
})

/** 当前类型是否承载静态凭据。 */
const supportsStaticCredentials = computed(() => STATIC_CREDENTIAL_TYPES.includes(form.storageType))

const secretPlaceholder = computed(() => (storedAccessKeyId.value
  ? '已保存密钥，留空即保持不变'
  : '仅填写时不回显，请确认无误'))

/** 表单覆盖的键集合（按类型不同）。 */
function knownKeys(type) {
  const keys = new Set([...COMMON_KEYS, ...(TYPE_FIELDS[type] || []).map((field) => field.key)])
  if (STATIC_CREDENTIAL_TYPES.includes(type)) {
    STATIC_CREDENTIAL_KEYS.forEach((key) => keys.add(key))
  }
  return keys
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
  // 密钥只写不读，服务端不会回显：这里能拿到的只有 accessKeyId。
  // 每次灌入都把它当成「当前已保存的值」，并清空输入框与移除标记。
  const credentialTypes = STATIC_CREDENTIAL_TYPES.includes(type)
  form.accessKeyId = credentialTypes ? (next.accessKeyId || '') : ''
  form.secretAccessKey = ''
  form.removeCredentials = false
  storedAccessKeyId.value = form.accessKeyId
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
  // 静态凭据同样不能跨类型沿用：AK/SK 是签发到具体云厂商的，
  // 换成另一家的存储类型后旧密钥不但没用，还会让「这个 catalog 用哪把钥匙」变得难判断
  form.accessKeyId = ''
  form.secretAccessKey = ''
  form.removeCredentials = false
  storedAccessKeyId.value = ''
  carried.value = splitCarried(carried.value, next)
  push()
}

function removeLocation(index) {
  form.allowedLocations.splice(index, 1)
  push()
}

/**
 * 收敛成服务端要的形状：丢掉空串与空数组，布尔值原样带上。
 *
 * <p><b>静态凭据的三个分支对应服务端的三种语义</b>（见服务端 `StorageConfigs`
 * 的 `mergeStaticCredentials`）：都填 = 设置、都为空串 = 清除、都不出现 = 保持不变。
 * 因此这里空值不是「删除字段」那么简单——对密钥来说删掉字段意味着「保持」，
 * 想清除必须显式提交一对空串，这也是「移除」要单独一个按钮而不是「清空输入框」的原因：
 * 一个空的输入框同时可能是「我没动它」和「我要删掉它」，UI 上必须分得开。
 */
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

  if (supportsStaticCredentials.value) {
    const accessKeyId = (form.accessKeyId || '').trim()
    const secretAccessKey = (form.secretAccessKey || '').trim()
    if (form.removeCredentials) {
      config.accessKeyId = ''
      config.secretAccessKey = ''
    } else {
      if (accessKeyId) {
        config.accessKeyId = accessKeyId
      } else {
        delete config.accessKeyId
      }
      // 密钥留空即「不提交该字段」，服务端会保持库中已保存的那一份
      if (secretAccessKey) {
        config.secretAccessKey = secretAccessKey
      } else {
        delete config.secretAccessKey
      }
    }
  } else {
    STATIC_CREDENTIAL_KEYS.forEach((key) => delete config[key])
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
  const credentialIssue = validateCredentials()
  if (credentialIssue) return credentialIssue
  return ''
}

/**
 * 静态凭据的前置校验。
 *
 * <p>两条规则与服务端一致，提前在这里拦一次是因为它们的可发现性太差：
 * 密钥只写不读，用户看不到「服务端手上是什么」；而 storageName 与静态凭据
 * 在表单里相隔很远，「保存失败」时用户很难把两者联系起来。
 */
function validateCredentials() {
  if (!supportsStaticCredentials.value || form.removeCredentials) return ''
  const accessKeyId = (form.accessKeyId || '').trim()
  const secretAccessKey = (form.secretAccessKey || '').trim()
  if (secretAccessKey && !accessKeyId) {
    return '填写了 secretAccessKey，但 accessKeyId 为空'
  }
  if (accessKeyId && !secretAccessKey && accessKeyId !== storedAccessKeyId.value) {
    return '更换 accessKeyId 时必须同时填写 secretAccessKey（服务端不回显密钥，无法沿用）'
  }
  if (accessKeyId && String(form.storageName || '').trim()) {
    return '静态凭据与具名存储（storageName）互斥：两者都在指定用哪一组密钥，请只保留一种'
  }
  if (accessKeyId && !props.staticCredentialsEnabled) {
    return '本部署未开启静态凭据（服务端未配置 paimon.rest.storage.credential-secret-key），'
      + '请留空以使用服务端配置的密钥'
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
