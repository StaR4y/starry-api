import { t } from '@/i18n';
import type { DocContent } from '@/api/documentation';

export const documentationTemplates: { id: string; name: string; content: DocContent }[] = [
  {
    id: 'blank',
    get name() {
      return t('空白文档');
    },
    content: {
      get title() {
        return t('新文档');
      },
      get category() {
        return t('使用指南');
      },
      summary: '',
      sortOrder: 100,
      get body() {
        return t('## 开始\n\n');
      },
    },
  },
  {
    id: 'guide',
    get name() {
      return t('操作指南');
    },
    content: {
      get title() {
        return t('操作指南');
      },
      get category() {
        return t('使用指南');
      },
      summary: '',
      sortOrder: 100,
      get body() {
        return t(
          '## 开始之前\n\n填写需要准备的内容。\n\n## 第一步\n\n填写操作步骤。\n\n## 第二步\n\n填写操作步骤，可通过附件工具插入截图或视频。\n\n## 完成后\n\n填写如何确认操作成功。\n',
        );
      },
    },
  },
  {
    id: 'client',
    get name() {
      return t('客户端接入');
    },
    content: {
      get title() {
        return t('客户端接入');
      },
      get category() {
        return t('连接客户端');
      },
      summary: '',
      sortOrder: 100,
      get body() {
        return t(
          '## 安装客户端\n\n[CC Switch 官方网站](https://ccswitch.io)\n\n## 一键导入\n\n打开 [API Key](#/api-keys) 页，选择密钥的 **客户端配置**，在 **CC Switch 一键连接** 中点击 **打开**。\n\n## 手动配置\n\n```json\n{\n  "base_url": "{{api_base_url}}",\n  "api_key": "YOUR_API_KEY",\n  "model": "MODEL_ID"\n}\n```\n\n## 验证连接\n\n填写测试方法与预期结果。\n',
        );
      },
    },
  },
  {
    id: 'faq',
    get name() {
      return t('常见问题');
    },
    content: {
      get title() {
        return t('常见问题');
      },
      get category() {
        return t('遇到问题');
      },
      summary: '',
      sortOrder: 100,
      get body() {
        return t(
          '## 问题一\n\n描述原因与解决步骤。\n\n## 问题二\n\n描述原因与解决步骤。\n\n## 仍需帮助\n\n通过 [我的工单](#/tickets) 联系管理员，附上已隐藏密钥的错误信息。\n',
        );
      },
    },
  },
];
