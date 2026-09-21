<script setup lang="ts">
  import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import {
    ArrowLeft,
    ArrowRight,
    Plus,
    Refresh,
    Search,
    Paperclip,
    Setting,
    ChatDotRound,
    Service,
    User,
    EditPen,
    CircleCheck,
    RefreshLeft,
    Download,
  } from '@element-plus/icons-vue';
  import PageSection from '@/components/PageSection.vue';
  import { useAuthStore } from '@/stores/auth';
  import { ApiError } from '@/utils/http';
  import { formatDateTime } from '@/utils/format';
  import {
    ticketApi,
    ticketCategories,
    ticketStatuses,
    type TicketPage,
    type TicketThread,
    type TicketSubmission,
    type TicketStatus,
    type Attachment,
  } from '@/api/tickets';
  import TicketComposer from './TicketComposer.vue';
  import { useTicketDraftGuard } from './useTicketDraftGuard';

  const auth = useAuthStore();
  const route = useRoute();
  const router = useRouter();
  const id = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''));
  const page = ref(1);
  const status = ref('');
  const search = ref('');
  const data = ref<TicketPage>();
  const thread = ref<TicketThread>();
  const loading = ref(false);
  const failed = ref(false);
  const saving = ref(false);
  const olderLoading = ref(false);
  const composer = ref<InstanceType<typeof TicketComposer>>();
  const replyArea = ref<HTMLElement>();
  const downloading = ref('');
  let loadSequence = 0;
  const ended = computed(() => ['RESOLVED', 'CLOSED'].includes(thread.value?.ticket.status ?? ''));
  const dirty = computed(() => composer.value?.dirty ?? false);
  const total = computed(() =>
    Object.values(data.value?.counts ?? {}).reduce((sum, n) => sum + (n ?? 0), 0),
  );
  const listTitle = computed(() => (auth.isAdmin ? '工单管理' : '我的工单'));
  const title = computed(() =>
    id.value ? thread.value?.ticket.title || '工单详情' : listTitle.value,
  );
  const date = (value: string) =>
    new Date(value).toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  useTicketDraftGuard(dirty, saving);

  async function load() {
    const sequence = ++loadSequence;
    loading.value = true;
    failed.value = false;
    try {
      if (id.value) {
        const result = await ticketApi.thread(id.value);
        if (sequence === loadSequence) thread.value = result;
      } else {
        const result = await ticketApi.list({
          page: page.value,
          pageSize: 20,
          status: status.value,
          search: search.value.trim(),
        });
        if (sequence === loadSequence) data.value = result;
      }
    } catch {
      if (sequence === loadSequence) failed.value = true;
    } finally {
      if (sequence === loadSequence) loading.value = false;
    }
  }
  function filter() {
    page.value = 1;
    void load();
  }
  async function older() {
    if (!thread.value || olderLoading.value || loading.value) return;
    const currentId = id.value;
    const sequence = loadSequence;
    const before = thread.value.nextBefore;
    olderLoading.value = true;
    try {
      const result = await ticketApi.thread(currentId, before);
      if (id.value !== currentId || sequence !== loadSequence || !thread.value) return;
      const known = new Set(thread.value.messages.map((m) => m.id));
      thread.value.messages = [
        ...result.messages.filter((m) => !known.has(m.id)),
        ...thread.value.messages,
      ];
      thread.value.hasMore = result.hasMore;
      thread.value.nextBefore = result.nextBefore;
    } catch {
      // Shared HTTP client reports errors.
    } finally {
      olderLoading.value = false;
    }
  }
  async function focusReply() {
    await nextTick();
    replyArea.value?.scrollIntoView({ behavior: 'auto', block: 'center' });
    replyArea.value?.querySelector('textarea')?.focus({ preventScroll: true });
  }
  async function reply(input: TicketSubmission, files: File[]) {
    if (!thread.value || saving.value || loading.value || ended.value) return;
    saving.value = true;
    try {
      thread.value.ticket = await ticketApi.reply(
        id.value,
        { ...input, version: thread.value.ticket.version },
        files,
      );
      composer.value?.clear();
      await load();
      ElMessage.success('回复已发送');
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) await load();
    } finally {
      saving.value = false;
    }
  }
  async function changeStatus(next: TicketStatus) {
    if (!thread.value || saving.value || loading.value || next === thread.value.ticket.status)
      return;
    const currentId = id.value;
    if (next === 'CLOSED') {
      const confirmed = await ElMessageBox.confirm('确认关闭此工单？', '关闭工单', {
        confirmButtonText: '关闭工单',
        cancelButtonText: '取消',
      })
        .then(() => true)
        .catch(() => false);
      if (!confirmed) return;
    }
    if (!thread.value || currentId !== id.value || saving.value || loading.value) return;
    saving.value = true;
    try {
      thread.value.ticket = await ticketApi.status(currentId, next, thread.value.ticket.version);
      await load();
      ElMessage.success('工单状态已更新');
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) await load();
    } finally {
      saving.value = false;
    }
  }
  async function download(file: Attachment) {
    if (downloading.value) return;
    downloading.value = file.id;
    try {
      await ticketApi.download(id.value, file);
    } catch {
      // Shared HTTP client reports errors.
    } finally {
      downloading.value = '';
    }
  }
  watch(
    id,
    () => {
      thread.value = undefined;
      void load();
    },
    { immediate: true },
  );
  onBeforeUnmount(() => {
    ++loadSequence;
  });
</script>

<template>
  <PageSection
    :title="title"
    :subtitle="id ? '工单 #' + id.slice(0, 8) : '支持中心'"
    :icon="Service"
    class="tickets"
  >
    <template #actions>
      <el-button
        v-if="id"
        :icon="ArrowLeft"
        :disabled="saving"
        @click="router.push({ name: 'tickets' })"
      >
        返回工单列表
      </el-button>
      <el-tooltip v-else-if="auth.isAdmin" content="工单通知设置">
        <el-button
          :icon="Setting"
          aria-label="工单通知设置"
          @click="router.push({ name: 'settings-tickets' })"
        />
      </el-tooltip>
      <el-tooltip content="刷新">
        <el-button
          :icon="Refresh"
          aria-label="刷新工单"
          :loading="loading"
          :disabled="saving || olderLoading"
          @click="load"
        />
      </el-tooltip>
      <el-button
        v-if="!id"
        type="primary"
        :icon="Plus"
        @click="router.push({ name: 'ticket-create' })"
      >
        新建工单
      </el-button>
      <el-button
        v-else-if="thread && !ended"
        type="primary"
        :icon="EditPen"
        :disabled="saving || loading"
        @click="focusReply"
      >
        {{ auth.isAdmin ? '回复工单' : '补充回复' }}
      </el-button>
    </template>

    <template v-if="!id">
      <div class="tickets__filters">
        <el-tabs v-model="status" @tab-change="filter">
          <el-tab-pane name="" :label="'全部 ' + total" />
          <el-tab-pane
            v-for="(label, value) in ticketStatuses"
            :key="value"
            :name="value"
            :label="label + ' ' + (data?.counts[value] ?? 0)"
          />
        </el-tabs>
        <el-input
          v-model="search"
          aria-label="搜索工单"
          :placeholder="auth.isAdmin ? '搜索标题、编号或用户' : '搜索标题或编号'"
          :prefix-icon="Search"
          clearable
          :maxlength="160"
          @keyup.enter="filter"
          @clear="filter"
        />
      </div>
      <div v-loading="loading" class="ticket-list" aria-label="工单列表">
        <el-empty v-if="failed" description="工单加载失败" :image-size="80">
          <el-button :icon="Refresh" @click="load">重新加载</el-button>
        </el-empty>
        <template v-else-if="data?.items.length">
          <div class="ticket-list__heading">
            <span>工单</span><span>状态</span><span>最近更新</span><span />
          </div>
          <button
            v-for="ticket in data.items"
            :key="ticket.id"
            class="ticket-row"
            type="button"
            @click="router.push({ name: 'ticket-detail', params: { id: ticket.id } })"
          >
            <div class="ticket-row__main">
              <strong>{{ ticket.title }}</strong>
              <div>
                <span class="ticket-number">#{{ ticket.id.slice(0, 8) }}</span>
                <span>{{ ticketCategories[ticket.category] }}</span>
                <span v-if="auth.isAdmin">{{ ticket.username }}</span>
                <span><el-icon><ChatDotRound /></el-icon> {{ ticket.messageCount }}</span>
                <time class="ticket-row__mobile-date" :datetime="ticket.updatedAt">{{
                  date(ticket.updatedAt)
                }}</time>
              </div>
            </div>
            <span class="ticket-status" :class="'is-' + ticket.status.toLowerCase()"><i />{{ ticketStatuses[ticket.status] }}</span>
            <time class="ticket-row__date" :datetime="ticket.updatedAt">{{
              date(ticket.updatedAt)
            }}</time>
            <el-icon class="ticket-row__arrow"><ArrowRight /></el-icon>
          </button>
        </template>
        <el-empty
          v-else-if="!loading"
          :description="status || search ? '没有匹配的工单' : '暂无工单'"
          :image-size="80"
        />
      </div>
      <el-pagination
        v-if="data && data.total > 20"
        v-model:current-page="page"
        :total="data.total"
        :page-size="20"
        layout="prev, pager, next"
        class="tickets__pagination"
        @current-change="load"
      />
    </template>

    <template v-else>
      <el-empty v-if="failed && !thread" description="无法读取工单" :image-size="80">
        <el-button :icon="Refresh" @click="load">重新加载</el-button>
      </el-empty>
      <div v-else-if="thread" v-loading="loading" class="ticket-detail">
        <div class="ticket-detail__main">
          <div class="ticket-section-heading">
            <h2>沟通记录</h2>
            <span>{{ thread.ticket.messageCount }} 条记录</span>
          </div>
          <div v-if="failed" class="ticket-refresh-error" role="status">
            更新失败，当前显示上次加载的记录。<el-button link type="primary" @click="load">
              重试
            </el-button>
          </div>
          <div class="ticket-thread">
            <el-button
              v-if="thread.hasMore"
              class="ticket-thread__older"
              text
              :loading="olderLoading"
              :disabled="saving || loading"
              @click="older"
            >
              查看更早的记录
            </el-button>
            <template v-for="message in thread.messages" :key="message.id">
              <div v-if="message.kind === 'STATUS'" class="ticket-event">
                <el-icon><CircleCheck /></el-icon>
                <span>{{ message.actorName }} 将状态改为 {{ ticketStatuses[message.status] }}</span>
                <time :datetime="message.createdAt">{{ date(message.createdAt) }}</time>
              </div>
              <article v-else class="ticket-message" :class="{ 'is-admin': message.admin }">
                <div class="ticket-message__avatar">
                  <el-icon><Service v-if="message.admin" /><User v-else /></el-icon>
                </div>
                <div class="ticket-message__content">
                  <header>
                    <strong>{{ message.actorName }}</strong>
                    <span class="ticket-message__role">{{
                      message.admin ? '管理员' : '用户'
                    }}</span>
                    <time :datetime="message.createdAt">{{ date(message.createdAt) }}</time>
                  </header>
                  <div class="ticket-message__body">{{ message.body }}</div>
                  <div v-if="message.attachments.length" class="ticket-message__attachments">
                    <button
                      v-for="file in message.attachments"
                      :key="file.id"
                      type="button"
                      :disabled="!!downloading"
                      :aria-label="'下载 ' + file.filename"
                      @click="download(file)"
                    >
                      <el-icon><Paperclip /></el-icon><span>{{ file.filename }}</span><small>{{ Math.ceil(file.sizeBytes / 1024) }} KB</small><el-icon><Download /></el-icon>
                    </button>
                  </div>
                </div>
              </article>
            </template>
          </div>
          <section ref="replyArea" class="ticket-detail__reply">
            <div class="ticket-section-heading">
              <h2>{{ auth.isAdmin ? '回复工单' : '补充回复' }}</h2>
              <span v-if="dirty">有未提交内容</span>
            </div>
            <div v-if="ended" class="ticket-detail__closed">
              <span class="ticket-status" :class="'is-' + thread.ticket.status.toLowerCase()"><i />{{ ticketStatuses[thread.ticket.status] }}</span>
              <el-button
                :icon="RefreshLeft"
                :loading="saving"
                :disabled="loading"
                @click="changeStatus('OPEN')"
              >
                重新打开工单
              </el-button>
            </div>
            <TicketComposer
              v-show="!ended"
              :key="id"
              ref="composer"
              :admin="auth.isAdmin"
              :busy="saving || loading || ended"
              @submit="reply"
            />
          </section>
        </div>
        <aside class="ticket-detail__aside" aria-label="工单信息">
          <div class="ticket-section-heading"><h2>工单信息</h2></div>
          <div class="ticket-detail__state">
            <span class="ticket-status" :class="'is-' + thread.ticket.status.toLowerCase()"><i />{{ ticketStatuses[thread.ticket.status] }}</span>
            <el-select
              v-if="auth.isAdmin"
              :model-value="thread.ticket.status"
              aria-label="工单状态"
              :disabled="saving || loading"
              @change="changeStatus"
            >
              <el-option
                v-for="(label, value) in ticketStatuses"
                :key="value"
                :label="label"
                :value="value"
              />
            </el-select>
          </div>
          <dl class="ticket-metadata">
            <div>
              <dt>问题分类</dt>
              <dd>{{ ticketCategories[thread.ticket.category] }}</dd>
            </div>
            <div>
              <dt>提交人</dt>
              <dd>{{ thread.ticket.username }}</dd>
            </div>
            <div>
              <dt>创建时间</dt>
              <dd>
                <time :datetime="thread.ticket.createdAt">{{
                  formatDateTime(thread.ticket.createdAt)
                }}</time>
              </dd>
            </div>
            <div>
              <dt>最近更新</dt>
              <dd>
                <time :datetime="thread.ticket.updatedAt">{{
                  formatDateTime(thread.ticket.updatedAt)
                }}</time>
              </dd>
            </div>
            <div class="ticket-metadata__number">
              <dt>工单编号</dt>
              <dd>{{ thread.ticket.id }}</dd>
            </div>
          </dl>
          <el-button
            v-if="!ended && !auth.isAdmin"
            :icon="CircleCheck"
            :loading="saving"
            :disabled="loading"
            @click="changeStatus('CLOSED')"
          >
            关闭工单
          </el-button>
        </aside>
      </div>
      <el-skeleton v-else :rows="10" animated />
    </template>
  </PageSection>
</template>

<style scoped lang="scss">
  .tickets {
    min-width: 0;
    :deep(.page-section__identity) {
      flex: 1;
      min-width: 0;
    }
    :deep(.page-section__identity > div) {
      min-width: 0;
    }
    :deep(.page-section__title) {
      overflow-wrap: anywhere;
      line-height: 1.5;
    }
    :deep(.page-section__actions .el-button + .el-button) {
      margin-left: 0;
    }
  }
  .tickets__filters {
    display: flex;
    align-items: center;
    gap: 24px;
    border-bottom: 1px solid var(--kp-divider);
    .el-tabs {
      min-width: 0;
      flex: 1;
    }
    > .el-input {
      width: 230px;
      flex-shrink: 0;
      margin-bottom: 12px;
    }
    :deep(.el-tabs__header) {
      margin-bottom: 0;
    }
    :deep(.el-tabs__nav-wrap::after) {
      display: none;
    }
  }
  .ticket-list {
    min-height: 240px;
  }
  .ticket-list__heading,
  .ticket-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 105px 115px 16px;
    gap: 20px;
    align-items: center;
  }
  .ticket-list__heading {
    padding: 14px 12px;
    color: var(--kp-text-muted);
    font-size: 12px;
  }
  .ticket-row {
    width: 100%;
    text-align: left;
    padding: 18px 12px;
    border: 0;
    border-top: 1px solid var(--kp-divider);
    background: transparent;
    color: var(--kp-text);
    font: inherit;
    cursor: pointer;
    transition: background 0.16s ease;
    &:hover {
      background: var(--kp-primary-soft);
    }
    &:focus-visible {
      outline: 2px solid var(--kp-primary);
      outline-offset: -2px;
    }
    &__main {
      min-width: 0;
      strong {
        font-size: 14px;
        font-weight: 600;
        overflow-wrap: anywhere;
        line-height: 1.6;
      }
      > div {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 12px;
        margin-top: 6px;
        font-size: 12px;
        color: var(--kp-text-muted);
        overflow-wrap: anywhere;
        > span {
          display: inline-flex;
          align-items: center;
          gap: 4px;
          min-width: 0;
          max-width: 100%;
        }
      }
    }
    time,
    &__arrow {
      color: var(--kp-text-muted);
      font-size: 12px;
    }
    &__mobile-date {
      display: none;
    }
  }
  .ticket-number {
    font:
      12px ui-monospace,
      monospace;
    color: var(--kp-text-muted);
  }
  .ticket-status {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    white-space: nowrap;
    font-size: 12px;
    color: var(--kp-text-secondary);
    i {
      width: 6px;
      height: 6px;
      flex-shrink: 0;
      border-radius: 50%;
      background: currentColor;
    }
    &.is-open {
      color: var(--el-color-warning);
    }
    &.is-in_progress {
      color: var(--kp-primary);
    }
    &.is-waiting_user {
      color: var(--kp-primary);
    }
    &.is-resolved {
      color: var(--el-color-success);
    }
  }
  .tickets__pagination {
    justify-content: flex-end;
    margin-top: 20px;
  }
  .ticket-detail {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 240px;
    gap: 28px;
    border-top: 1px solid var(--kp-divider);
    padding-top: 24px;
    &__main {
      min-width: 0;
    }
    &__aside {
      min-width: 0;
      border-left: 1px solid var(--kp-divider);
      padding-left: 24px;
    }
    &__state {
      display: grid;
      gap: 16px;
      margin-bottom: 22px;
    }
    &__reply {
      padding-top: 24px;
      margin-top: 12px;
      border-top: 1px solid var(--kp-divider);
      scroll-margin-top: 90px;
    }
    &__closed {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      padding: 8px 0;
    }
  }
  .ticket-section-heading {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 18px;
    h2 {
      margin: 0;
      font-size: 15px;
      font-weight: 600;
      letter-spacing: 0;
    }
    > span {
      color: var(--kp-text-muted);
      font-size: 12px;
    }
  }
  .ticket-metadata {
    display: grid;
    gap: 20px;
    margin: 0 0 24px;
    font-size: 12px;
    div {
      min-width: 0;
    }
    dt {
      color: var(--kp-text-muted);
      margin-bottom: 6px;
    }
    dd {
      margin: 0;
      color: var(--kp-text-secondary);
      line-height: 1.6;
      overflow-wrap: anywhere;
    }
    &__number dd {
      font-family: ui-monospace, monospace;
      font-size: 11px;
    }
  }
  .ticket-thread__older {
    display: block;
    margin: 0 auto 14px;
  }
  .ticket-message {
    display: grid;
    grid-template-columns: 32px minmax(0, 1fr);
    gap: 12px;
    padding: 18px 0;
    & + & {
      border-top: 1px solid var(--kp-divider);
    }
    &__avatar {
      display: grid;
      place-items: center;
      width: 32px;
      height: 32px;
      border: 1px solid var(--kp-divider);
      border-radius: 6px;
      color: var(--kp-text-secondary);
    }
    &.is-admin &__avatar {
      background: var(--kp-primary-soft);
      color: var(--kp-primary);
      border-color: transparent;
    }
    &__content {
      min-width: 0;
    }
    header {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 13px;
      flex-wrap: wrap;
      min-height: 32px;
      strong {
        overflow-wrap: anywhere;
        min-width: 0;
      }
      time {
        margin-left: auto;
        color: var(--kp-text-muted);
        font-size: 12px;
      }
    }
    &__role {
      color: var(--kp-text-muted);
      font-size: 11px;
    }
    &__body {
      margin-top: 8px;
      font-size: 14px;
      line-height: 1.85;
      white-space: pre-wrap;
      overflow-wrap: anywhere;
    }
    &__attachments {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-top: 14px;
      button {
        max-width: 100%;
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 8px 10px;
        border: 1px solid var(--kp-divider);
        border-radius: 6px;
        background: transparent;
        color: var(--kp-primary);
        cursor: pointer;
        font: inherit;
        font-size: 12px;
        text-align: left;
        &:hover {
          background: var(--kp-primary-soft);
        }
        &:disabled {
          cursor: wait;
          opacity: 0.6;
        }
        span {
          min-width: 0;
          overflow-wrap: anywhere;
        }
        small {
          color: var(--kp-text-muted);
          white-space: nowrap;
        }
        .el-icon {
          flex-shrink: 0;
        }
      }
    }
  }
  .ticket-event {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--kp-text-muted);
    font-size: 12px;
    padding: 12px 0;
    overflow-wrap: anywhere;
    > span {
      min-width: 0;
    }
    > .el-icon {
      flex-shrink: 0;
    }
    time {
      margin-left: auto;
      white-space: nowrap;
    }
  }
  .ticket-refresh-error {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    align-items: center;
    color: var(--el-color-warning);
    font-size: 12px;
  }
  @media (max-width: 1100px) {
    .tickets__filters {
      flex-direction: column;
      align-items: stretch;
      gap: 10px;
      > .el-input {
        width: 100%;
      }
    }
    .ticket-detail {
      grid-template-columns: minmax(0, 1fr) 200px;
      gap: 20px;
      &__aside {
        padding-left: 20px;
      }
    }
  }
  @media (max-width: 900px) {
    .ticket-detail {
      grid-template-columns: 1fr;
    }
    .ticket-detail__aside {
      order: -1;
      border-left: 0;
      border-bottom: 1px solid var(--kp-divider);
      padding: 0 0 20px;
    }
    .ticket-detail__state {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      align-items: center;
      .el-select {
        width: 160px;
      }
    }
    .ticket-metadata {
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 14px;
      &__number {
        grid-column: 1 / -1;
      }
    }
  }
  @media (max-width: 600px) {
    .ticket-list__heading,
    .ticket-row {
      grid-template-columns: minmax(0, 1fr) 80px;
      gap: 12px;
      padding-left: 0;
      padding-right: 0;
    }
    .ticket-list__heading > :nth-child(n + 3),
    .ticket-row__date,
    .ticket-row__arrow {
      display: none;
    }
    .ticket-row__mobile-date {
      display: inline;
    }
    .ticket-message {
      grid-template-columns: 28px minmax(0, 1fr);
      gap: 8px;
      &__avatar {
        width: 28px;
        height: 28px;
      }
      header time {
        width: 100%;
        margin-left: 0;
      }
    }
    .ticket-event {
      flex-wrap: wrap;
      time {
        width: 100%;
        margin-left: 20px;
      }
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .ticket-row {
      transition: none;
    }
  }
</style>
