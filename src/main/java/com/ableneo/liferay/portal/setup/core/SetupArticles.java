package com.ableneo.liferay.portal.setup.core;

/*
 * #%L
 * Liferay Portal DB Setup core
 * %%
 * Original work Copyright (C) 2016 - 2018 mimacom ag
 * Modified work Copyright (C) 2018 - 2020 ableneo, s. r. o.
 * %%
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * #L%
 */

import java.io.IOException;
import java.util.*;

import com.ableneo.liferay.portal.setup.SetupConfigurationThreadLocal;
import com.ableneo.liferay.portal.setup.core.util.*;
import com.ableneo.liferay.portal.setup.domain.*;
import com.liferay.asset.kernel.model.AssetEntry;
import com.liferay.asset.kernel.model.AssetLinkConstants;
import com.liferay.asset.kernel.service.AssetEntryLocalServiceUtil;
import com.liferay.asset.kernel.service.AssetLinkLocalServiceUtil;
import com.liferay.dynamic.data.lists.model.DDLRecordSet;
import com.liferay.dynamic.data.lists.service.DDLRecordSetLocalServiceUtil;
import com.liferay.dynamic.data.mapping.exception.TemplateDuplicateTemplateKeyException;
import com.liferay.dynamic.data.mapping.model.*;
import com.liferay.dynamic.data.mapping.service.DDMStructureLocalServiceUtil;
import com.liferay.dynamic.data.mapping.service.DDMTemplateLocalServiceUtil;
import com.liferay.dynamic.data.mapping.util.DDMUtil;
import com.liferay.journal.model.JournalArticle;
import com.liferay.journal.model.JournalArticleConstants;
import com.liferay.journal.model.JournalFolder;
import com.liferay.journal.service.JournalArticleLocalServiceUtil;
import com.liferay.petra.string.StringPool;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.RoleConstants;
import com.liferay.portal.kernel.search.Indexer;
import com.liferay.portal.kernel.search.IndexerRegistryUtil;
import com.liferay.portal.kernel.security.permission.ActionKeys;
import com.liferay.portal.kernel.service.ClassNameLocalServiceUtil;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.ServiceContextThreadLocal;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.LocalizationUtil;
import com.liferay.portal.kernel.util.PortalUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.kernel.workflow.WorkflowConstants;

/**
 * Created by mapa, guno..
 */
public final class SetupArticles {

    private static final Log LOG = LogFactoryUtil.getLog(SetupArticles.class);
    private static final HashMap<String, List<String>> DEFAULT_PERMISSIONS;
    private static final HashMap<String, List<String>> DEFAULT_DDM_PERMISSIONS;
    private static final int ARTICLE_PUBLISH_YEAR = 2008;
    private static final int MIN_DISPLAY_ROWS = 10;

    static {
        DEFAULT_PERMISSIONS = new HashMap<>();
        DEFAULT_DDM_PERMISSIONS = new HashMap<>();
        List<String> actionsOwner = new ArrayList<>();

        actionsOwner.add(ActionKeys.VIEW);
        actionsOwner.add(ActionKeys.ADD_DISCUSSION);
        actionsOwner.add(ActionKeys.DELETE);
        actionsOwner.add(ActionKeys.DELETE_DISCUSSION);
        actionsOwner.add(ActionKeys.EXPIRE);
        actionsOwner.add(ActionKeys.PERMISSIONS);
        actionsOwner.add(ActionKeys.UPDATE);
        actionsOwner.add(ActionKeys.UPDATE_DISCUSSION);

        List<String> ddmActionsOwner = new ArrayList<>();

        ddmActionsOwner.add(ActionKeys.VIEW);
        ddmActionsOwner.add(ActionKeys.DELETE);
        ddmActionsOwner.add(ActionKeys.UPDATE);
        ddmActionsOwner.add(ActionKeys.PERMISSIONS);

        DEFAULT_PERMISSIONS.put(RoleConstants.OWNER, actionsOwner);
        DEFAULT_DDM_PERMISSIONS.put(RoleConstants.OWNER, ddmActionsOwner);

        List<String> actionsUser = new ArrayList<>();
        actionsUser.add(ActionKeys.VIEW);
        DEFAULT_PERMISSIONS.put(RoleConstants.USER, actionsUser);
        DEFAULT_DDM_PERMISSIONS.put(RoleConstants.USER, actionsUser);

        List<String> actionsGuest = new ArrayList<>();
        actionsGuest.add(ActionKeys.VIEW);
        DEFAULT_PERMISSIONS.put(RoleConstants.GUEST, actionsGuest);
        DEFAULT_DDM_PERMISSIONS.put(RoleConstants.GUEST, actionsGuest);
    }

    private SetupArticles() {

    }

    public static void setupSiteStructuresAndTemplates(final Site site, long groupId) throws PortalException {
        List<Structure> articleStructures = site.getArticleStructure();

        if (articleStructures != null) {
            long classNameId = ClassNameLocalServiceUtil.getClassNameId(JournalArticle.class);
            for (Structure structure : articleStructures) {
                addDDMStructure(structure, groupId, classNameId);
            }
        }

        List<Structure> ddlStructures = site.getDdlStructure();

        if (articleStructures != null) {
            long classNameId = ClassNameLocalServiceUtil.getClassNameId(DDLRecordSet.class);
            for (Structure structure : ddlStructures) {
                LOG.info(String.format("Adding DDL structure %1$s", structure.getName()));
                addDDMStructure(structure, groupId, classNameId);
            }
        }

        List<ArticleTemplate> articleTemplates = site.getArticleTemplate();
        if (articleTemplates != null) {
            for (ArticleTemplate template : articleTemplates) {
                try {
                    addDDMTemplate(template);
                } catch (TemplateDuplicateTemplateKeyException e) {
                    LOG.error(e);
                }
            }
        }
    }

    public static void setupSiteArticles(final List<Article> articles, final List<Adt> adts,
            final List<DdlRecordset> recordSets, final long groupId) throws PortalException {

        if (articles != null) {
            for (Article article : articles) {
                addJournalArticle(article, groupId);
            }
        }
        if (adts != null) {
            for (Adt template : adts) {
                try {
                    addDDMTemplate(template, groupId);
                } catch (TemplateDuplicateTemplateKeyException | IOException e) {
                    LOG.error(String.format("Error in adding ADT: %1$s", template.getName()), e);
                }
            }
        }
        if (recordSets != null) {
            for (DdlRecordset recordSet : recordSets) {
                try {
                    addDDLRecordSet(recordSet, groupId);
                } catch (TemplateDuplicateTemplateKeyException e) {
                    LOG.error(String.format("Error in adding DDLRecordSet: %1$s", recordSet.getName()), e);
                }
            }
        }
    }

    public static void addDDMStructure(final Structure structure, final long groupId, final long classNameId)
            throws PortalException {

        LOG.info(String.format("Adding Article structure %1$s", structure.getName()));
        Map<Locale, String> nameMap = new HashMap<>();
        Locale siteDefaultLocale = null;
        try {
            siteDefaultLocale = PortalUtil.getSiteDefaultLocale(groupId);
        } catch (PortalException e) {
            LOG.error(e);
        }
        String name = getStructureNameOrKey(structure);
        nameMap.put(siteDefaultLocale, name);
        Map<Locale, String> descMap = new HashMap<>();

        String content = null;
        DDMForm ddmForm = null;
        DDMFormLayout ddmFormLayout = null;
        try {
            content = ResourcesUtil.getFileContent(structure.getPath());
            ddmForm = DDMUtil.getDDMForm(content);
            if (ddmForm == null) {
                LOG.error("Can not parse given structure JSON content into Liferay DDMForm.");
                return;
            }
            ddmFormLayout = DDMUtil.getDefaultDDMFormLayout(ddmForm);
        } catch (IOException e) {
            LOG.error(String.format("Error Reading Structure File content for: %1$s", structure.getName()));
            return;
        } catch (PortalException e) {
            LOG.error("Can not parse given structure JSON content into Liferay DDMForm.", e);
            return;
        } catch (Exception e) {
            LOG.error(String.format(
                    "Other error while trying to get content of the structure file. Possibly wrong filesystem path (%1$s)?",
                    structure.getPath()), e);
            return;
        }

        Locale contentDefaultLocale = ddmForm.getDefaultLocale();
        if (!contentDefaultLocale.equals(siteDefaultLocale)) {
            nameMap.put(contentDefaultLocale, name);
        }

        DDMStructure ddmStructure =
                DDMStructureLocalServiceUtil.fetchStructure(groupId, classNameId, structure.getKey());

        long runAsUserId = SetupConfigurationThreadLocal.getRunAsUserId();
        long companyId = SetupConfigurationThreadLocal.getRunInCompanyId();
        if (ddmStructure != null) {
            LOG.info("Structure already exists and will be overwritten.");
            if (structure.getParent() != null && !structure.getParent().isEmpty()) {
                LOG.info(String.format("Setting up parent structure: %1$s", structure.getName()));
                DDMStructure parentStructure =
                        DDMStructureLocalServiceUtil.fetchStructure(groupId, classNameId, structure.getParent(), true);
                if (parentStructure != null) {
                    ddmStructure.setParentStructureId(parentStructure.getStructureId());
                } else {
                    LOG.info(String.format("Parent structure not found: %1$s", structure.getName()));
                }
            }

            DDMStructure ddmStructureSaved = DDMStructureLocalServiceUtil.updateStructure(runAsUserId,
                    ddmStructure.getStructureId(), ddmStructure.getParentStructureId(), nameMap, descMap, ddmForm,
                    ddmFormLayout, new ServiceContext());
            LOG.info(String.format("Template successfully updated: %1$s", structure.getName()));

            SetupPermissions.updatePermission(String.format("Structure %1$s", structure.getKey()), companyId,
                    ddmStructureSaved.getStructureId(),
                    DDMStructure.class.getName() + "-" + JournalArticle.class.getName(), structure.getRolePermissions(),
                    DEFAULT_DDM_PERMISSIONS);

            return;
        }

        DDMStructure newStructure =
                DDMStructureLocalServiceUtil.addStructure(runAsUserId, groupId, structure.getParent(), classNameId,
                        structure.getKey(), nameMap, descMap, ddmForm, ddmFormLayout, "json", 0, new ServiceContext());

        SetupPermissions.updatePermission(String.format("Structure %1$s", structure.getKey()), companyId,
                newStructure.getStructureId(), DDMStructure.class.getName() + "-" + JournalArticle.class.getName(),
                structure.getRolePermissions(), DEFAULT_DDM_PERMISSIONS);
        LOG.info(String.format("Added Article structure: %1$s", newStructure.getName()));
    }

    private static String getStructureNameOrKey(final Structure structure) {
        if (structure.getName() == null) {
            return structure.getName();
        }
        return structure.getKey();
    }

    public static void addDDMTemplate(final ArticleTemplate template) throws PortalException {

        LOG.info(String.format("Adding Article template %1$s", template.getName()));
        long classNameId = ClassNameLocalServiceUtil.getClassNameId(DDMStructure.class);
        long resourceClassnameId = ClassNameLocalServiceUtil.getClassNameId(JournalArticle.class);
        Map<Locale, String> nameMap = new HashMap<>();
        long groupId = SetupConfigurationThreadLocal.getRunInGroupId();
        Locale siteDefaultLocale = PortalUtil.getSiteDefaultLocale(groupId);
        String name = template.getName();
        if (name == null) {
            name = template.getKey();
        }
        nameMap.put(siteDefaultLocale, name);
        Map<Locale, String> descMap = new HashMap<>();

        String script;
        try {
            script = ResourcesUtil.getFileContent(template.getPath());
        } catch (Exception e) {
            LOG.error(String.format("Error Reading Template File content for: %1$s", template.getName()));
            return;
        }

        long classPK = 0;
        if (template.getArticleStructureKey() != null) {
            try {
                classPK = ResolverUtil.getStructureId(template.getArticleStructureKey(), groupId, JournalArticle.class,
                        false);
            } catch (Exception e) {
                LOG.error(String.format(
                        "Given article structure with ID: %1$s can not be found. Therefore, article template can not be added/changed.",
                        template.getArticleStructureKey()), e);
                return;
            }
        }

        DDMTemplate ddmTemplate = null;
        try {
            ddmTemplate = DDMTemplateLocalServiceUtil.fetchTemplate(groupId, classNameId, template.getKey());
        } catch (SystemException e) {
            LOG.error(String.format("Error while trying to find template with key: %1$s", template.getKey()), e);
        }

        if (ddmTemplate != null) {
            LOG.info("Template already exists and will be overwritten.");
            ddmTemplate.setNameMap(nameMap);
            ddmTemplate.setLanguage(template.getLanguage());
            ddmTemplate.setScript(script);
            ddmTemplate.setClassPK(classPK);
            ddmTemplate.setCacheable(template.isCacheable());

            DDMTemplateLocalServiceUtil.updateDDMTemplate(ddmTemplate);
            LOG.info(String.format("Template successfully updated: %1$s", ddmTemplate.getName()));
            return;
        }

        long runAsUserId = SetupConfigurationThreadLocal.getRunAsUserId();
        DDMTemplate newTemplate = DDMTemplateLocalServiceUtil.addTemplate(runAsUserId, groupId, classNameId, classPK,
                resourceClassnameId, template.getKey(), nameMap, descMap, DDMTemplateConstants.TEMPLATE_TYPE_DISPLAY,
                null, template.getLanguage(), script, template.isCacheable(), false, null, null, new ServiceContext());
        LOG.info(String.format("Added Article template: %1$s", newTemplate.getName()));
    }

    public static void addDDMTemplate(final Adt template, final long groupId) throws PortalException, IOException {

        LOG.info(String.format("Adding ADT %1$s", template.getName()));
        long classNameId = PortalUtil.getClassNameId(template.getClassName());
        long resourceClassnameId = Validator.isBlank(template.getResourceClassName())
                ? ClassNameLocalServiceUtil.getClassNameId(JournalArticle.class)
                : ClassNameLocalServiceUtil.getClassNameId(template.getResourceClassName());

        Map<Locale, String> nameMap = new HashMap<>();

        Locale siteDefaultLocale = PortalUtil.getSiteDefaultLocale(groupId);
        String name = template.getName();
        if (name == null) {
            name = template.getTemplateKey();
        }
        nameMap.put(siteDefaultLocale, name);

        Map<Locale, String> descriptionMap = new HashMap<>();
        descriptionMap.put(siteDefaultLocale, template.getDescription());

        DDMTemplate ddmTemplate = null;
        try {
            ddmTemplate = DDMTemplateLocalServiceUtil.fetchTemplate(groupId, classNameId, template.getTemplateKey());
        } catch (SystemException e) {
            LOG.error(String.format("Error while trying to find ADT with key: %1$s", template.getTemplateKey()));
        }

        String script = ResourcesUtil.getFileContent(template.getPath());

        if (ddmTemplate != null) {
            LOG.info("Template already exists and will be overwritten.");
            ddmTemplate.setLanguage(template.getLanguage());
            ddmTemplate.setNameMap(nameMap);
            ddmTemplate.setDescriptionMap(descriptionMap);
            ddmTemplate.setClassName(template.getClassName());
            ddmTemplate.setCacheable(template.isCacheable());
            ddmTemplate.setScript(script);

            DDMTemplateLocalServiceUtil.updateDDMTemplate(ddmTemplate);
            LOG.info(String.format("ADT successfully updated: %1$s", ddmTemplate.getName()));
            return;
        }

        long runAsUserId = SetupConfigurationThreadLocal.getRunAsUserId();
        DDMTemplate newTemplate =
                DDMTemplateLocalServiceUtil.addTemplate(runAsUserId, groupId, classNameId, 0, resourceClassnameId,
                        template.getTemplateKey(), nameMap, descriptionMap, DDMTemplateConstants.TEMPLATE_TYPE_DISPLAY,
                        null, template.getLanguage(), script, true, false, null, null, new ServiceContext());
        LOG.info(String.format("Added ADT: %1$s", newTemplate.getName()));
    }

    public static void addJournalArticle(final Article article, final long groupId) {
        LOG.info(String.format("Adding Journal Article %1$s", article.getTitle()));

        String content = null;
        long folderId = 0L;
        long companyId = SetupConfigurationThreadLocal.getRunInCompanyId();
        long runAsUserId = SetupConfigurationThreadLocal.getRunAsUserId();
        if (article.getArticleFolderPath() != null && !article.getArticleFolderPath().equals("")) {
            JournalFolder jf = WebFolderUtil.findWebFolder(companyId, groupId, runAsUserId,
                    article.getArticleFolderPath(), "", true);
            if (jf == null) {
                LOG.warn("Specified webfolder " + article.getArticleFolderPath() + " of article " + article.getTitle()
                        + " not found! Will put article into web content root folder!");
            } else {
                folderId = jf.getFolderId();
            }
        }
        try {
            content = ResourcesUtil.getFileContent(article.getPath());
            content = ResolverUtil.lookupAll(groupId, companyId, content, article.getPath());
        } catch (IOException e) {
            LOG.error(String.format("Error Reading Article File content for article ID: %1$s", article.getArticleId()));
        }
        Map<Locale, String> titleMap = TranslationMapUtil.getTranslationMap(article.getTitleTranslation(), groupId,
                article.getTitle(), String.format(" Article with title %1$s", article.getArticleId()));

        Locale articleDefaultLocale = LocaleUtil.fromLanguageId(LocalizationUtil.getDefaultLanguageId(content));
        if (!titleMap.containsKey(articleDefaultLocale)) {
            titleMap.put(articleDefaultLocale, article.getTitle());
        }

        Map<Locale, String> descriptionMap = null;
        if (article.getArticleDescription() != null && !article.getArticleDescription().isEmpty()) {
            descriptionMap = TranslationMapUtil.getTranslationMap(article.getDescriptionTranslation(), groupId,
                    article.getArticleDescription(),
                    String.format(" Article with description %1$s", article.getArticleId()));
            if (!descriptionMap.containsKey(articleDefaultLocale)) {
                descriptionMap.put(articleDefaultLocale, article.getArticleDescription());
            }
        }
        ServiceContext serviceContext = new ServiceContext();
        serviceContext.setScopeGroupId(groupId);

        JournalArticle journalArticle = null;

        boolean generatedId = (article.getArticleId().isEmpty());
        if (generatedId) {
            LOG.info(String.format("Article %1$s will have autogenerated ID.", article.getTitle()));
        } else {
            try {
                journalArticle = JournalArticleLocalServiceUtil.fetchLatestArticle(groupId, article.getArticleId(),
                        WorkflowConstants.STATUS_APPROVED);
            } catch (SystemException e) {
                LOG.error(String.format("Error while trying to find article with ID: %1$s", article.getArticleId()), e);
            }
        }

        try {
            if (journalArticle == null) {
                journalArticle = JournalArticleLocalServiceUtil.addArticle(runAsUserId, groupId, folderId, 0, 0,
                        article.getArticleId(), generatedId, JournalArticleConstants.VERSION_DEFAULT, titleMap,
                        descriptionMap, content, article.getArticleStructureKey(), article.getArticleTemplateKey(),
                        StringPool.BLANK, 1, 1, ARTICLE_PUBLISH_YEAR, 0, 0, 0, 0, 0, 0, 0, true, 0, 0, 0, 0, 0, true,
                        true, false, StringPool.BLANK, null, null, StringPool.BLANK, serviceContext);

                LOG.info(String.format("Added JournalArticle %1$s with ID: %2$s", journalArticle.getTitle(),
                        journalArticle.getArticleId()));
                Indexer bi = IndexerRegistryUtil.getIndexer(JournalArticle.class);
                if (bi != null) {
                    bi.reindex(journalArticle);
                }
            } else {
                LOG.info("Article " + article.getTitle() + " with article ID: " + article.getArticleId()
                        + " already exists. Will be overwritten.");
                journalArticle.setTitleMap(titleMap);
                journalArticle.setContent(content);
                journalArticle.setDescriptionMap(descriptionMap);

                JournalArticleLocalServiceUtil.updateJournalArticle(journalArticle);

                // if the folder changed, move it...
                if (journalArticle.getFolderId() != folderId) {
                    JournalArticleLocalServiceUtil.moveArticle(groupId, journalArticle.getArticleId(), folderId,
                            ServiceContextThreadLocal.getServiceContext());
                }
                LOG.info(String.format("Updated JournalArticle: %1$s", journalArticle.getTitle()));
            }
            TaggingUtil.associateTagsAndCategories(groupId, article, journalArticle);
            processRelatedAssets(article, journalArticle, runAsUserId, groupId, companyId);
            SetupPermissions.updatePermission(String.format("Article %1$s", journalArticle.getArticleId()),
                    companyId, journalArticle.getResourcePrimKey(), JournalArticle.class, article.getRolePermissions(),
                    DEFAULT_PERMISSIONS);
        } catch (PortalException e) {
            LOG.error(String.format("Error while trying to add/update Article with Title: %1$s", article.getTitle()),
                    e);
        }
    }

    private static void addDDLRecordSet(final DdlRecordset recordSet, final long groupId) throws PortalException {
        LOG.info(String.format("Adding DDLRecordSet %1$s", recordSet.getName()));
        Map<Locale, String> nameMap = new HashMap<>();
        Locale siteDefaultLocale = PortalUtil.getSiteDefaultLocale(groupId);
        nameMap.put(siteDefaultLocale, recordSet.getName());
        Map<Locale, String> descMap = new HashMap<>();
        descMap.put(siteDefaultLocale, recordSet.getDescription());
        DDLRecordSet ddlRecordSet = null;
        try {
            ddlRecordSet = DDLRecordSetLocalServiceUtil.fetchRecordSet(groupId, recordSet.getKey());
        } catch (SystemException e) {
            LOG.error(String.format("Error while trying to find DDLRecordSet with key: %1$s", recordSet.getKey()), e);
        }

        if (ddlRecordSet != null) {
            LOG.info("DDLRecordSet already exists and will be overwritten.");
            ddlRecordSet.setNameMap(nameMap);
            ddlRecordSet.setDescriptionMap(descMap);
            ddlRecordSet.setDDMStructureId(
                    ResolverUtil.getStructureId(recordSet.getDdlStructureKey(), groupId, DDLRecordSet.class, false));
            DDLRecordSetLocalServiceUtil.updateDDLRecordSet(ddlRecordSet);
            LOG.info(String.format("DDLRecordSet successfully updated: %1$s", recordSet.getName()));
            return;
        }

        long runAsUserId = SetupConfigurationThreadLocal.getRunAsUserId();
        DDLRecordSet newDDLRecordSet = DDLRecordSetLocalServiceUtil.addRecordSet(runAsUserId, groupId,
                ResolverUtil.getStructureId(recordSet.getDdlStructureKey(), groupId, DDLRecordSet.class, false),
                recordSet.getDdlStructureKey(), nameMap, descMap, MIN_DISPLAY_ROWS, 0, new ServiceContext());
        LOG.info(String.format("Added DDLRecordSet: %1$s", newDDLRecordSet.getName()));
    }

    public static void processRelatedAssets(final Article article, final JournalArticle ja, final long runAsUserId,
            final long groupId, final long companyId) {
        if (article.getRelatedAssets() != null) {
            RelatedAssets ras = article.getRelatedAssets();
            AssetEntry ae = null;
            if (ras.isClearAllAssets()) {

                try {
                    ae = AssetEntryLocalServiceUtil.getEntry(JournalArticle.class.getName(), ja.getResourcePrimKey());
                    AssetLinkLocalServiceUtil.deleteLinks(ae.getEntryId());
                } catch (PortalException | SystemException e) {
                    LOG.error(String.format("Problem clearing related assets of article %1$s", ja.getArticleId()), e);
                }
            }
            if (ras.getRelatedAsset() != null && !ras.getRelatedAsset().isEmpty()) {
                List<RelatedAsset> ra = ras.getRelatedAsset();
                for (RelatedAsset r : ra) {
                    String clazz = r.getAssetClass();
                    String clazzPrimKey = r.getAssetClassPrimaryKey();
                    String resolverHint = "Related asset for article " + ja.getArticleId() + " " + "clazz " + clazz
                            + ", " + "primary key " + clazzPrimKey;
                    clazzPrimKey = ResolverUtil.lookupAll(groupId, companyId, clazzPrimKey, resolverHint);

                    long id = 0;
                    try {
                        id = Long.parseLong(clazzPrimKey);
                    } catch (Exception ex) {
                        LOG.error("Class primary key is not parseable as long value.", ex);
                    }

                    try {

                        AssetEntry ae2 = AssetEntryLocalServiceUtil.getEntry(clazz, id);
                        AssetLinkLocalServiceUtil.addLink(runAsUserId, ae.getEntryId(), ae2.getEntryId(),
                                AssetLinkConstants.TYPE_RELATED, 1);
                    } catch (PortalException | SystemException e) {
                        LOG.error("Problem resolving related asset of article " + ja.getArticleId() + " with clazz "
                                + clazz + " primary key " + clazzPrimKey, e);
                    }

                }
            }

        }
    }

}
