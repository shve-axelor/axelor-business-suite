/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2019 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.studio.service.wkf;

import com.axelor.auth.db.Role;
import com.axelor.meta.db.MetaJsonField;
import com.axelor.meta.db.MetaPermission;
import com.axelor.meta.db.MetaPermissionRule;
import com.axelor.meta.db.repo.MetaPermissionRepository;
import com.axelor.meta.loader.XMLViews;
import com.axelor.meta.schema.actions.ActionAttrs;
import com.axelor.meta.schema.actions.ActionAttrs.Attribute;
import com.axelor.meta.schema.actions.ActionValidate;
import com.axelor.meta.schema.actions.ActionValidate.Alert;
import com.axelor.meta.schema.actions.ActionValidate.Info;
import com.axelor.meta.schema.actions.ActionValidate.Notify;
import com.axelor.meta.schema.actions.ActionValidate.Validator;
import com.axelor.studio.db.Filter;
import com.axelor.studio.db.WkfTransition;
import com.axelor.studio.service.StudioMetaService;
import com.axelor.studio.service.filter.FilterGroovyService;
import com.google.common.base.Joiner;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service class handle processing of WkfTransition. It creates buttons and actions from
 * WkfTransition to change the status.
 *
 * @author axelor
 */
class WkfTransitionService {

  private WkfService wkfService;

  private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private List<String> wkfButtonNames;

  @Inject private MetaPermissionRepository metaPermissionRepo;

  @Inject private FilterGroovyService filterGroovyService;

  @Inject private StudioMetaService metaService;

  @Inject
  protected WkfTransitionService(WkfService wkfService) {
    this.wkfService = wkfService;
  }

  /**
   * Root method to access the service. Method call different method to process transition. Create
   * wkf action that call on save of model to update status. It save all changes to ViewBuilder
   * linked with Workflow.
   */
  protected List<String[]> process() {

    log.debug("Processing transitions");
    List<String[]> actions = new ArrayList<String[]>();

    String action = "action-" + wkfService.wkfId;
    wkfButtonNames = new ArrayList<String>();

    List<ActionAttrs.Attribute> fields = proccessTransitions();

    if (!fields.isEmpty()) {
      String xml = getActionXML(action, fields);
      metaService.updateMetaAction(action, "action-attrs", xml, null);
      actions.add(new String[] {action});
    } else {
      metaService.removeMetaActions(action);
    }

    String actionsToRemove = wkfService.clearOldButtons(wkfButtonNames);
    metaService.removeMetaActions(actionsToRemove);

    return actions;
  }

  /**
   * Method process each WkfTransition and create RecordField. RecordField contains status and
   * condition to assign the status. Based on record fields wkf action will be created. It also add
   * button for transition if transition executed based on button.
   *
   * @return List of RecordField
   */
  private List<ActionAttrs.Attribute> proccessTransitions() {

    List<ActionAttrs.Attribute> fields = new ArrayList<ActionAttrs.Attribute>();

    Integer buttonSeq = -100;

    for (WkfTransition transition : wkfService.workflow.getTransitions()) {

      MetaJsonField status = wkfService.workflow.getStatusField();
      String condition =
          status.getName() + " == " + getTyped(transition.getSource().getSequence(), status);

      log.debug(
          "Processing transition : {}, isButton: {}",
          transition.getName(),
          transition.getIsButton());

      String filters = getFilters(transition.getConditions());
      if (filters != null) {
        condition += " && (" + filters + ")";
      }

      if (transition.getIsButton()) {
        buttonSeq++;
        addButton(transition, condition, buttonSeq);
        continue;
      }

      log.debug("Conditions : {}", transition.getConditions());

      ActionAttrs.Attribute attrs = new ActionAttrs.Attribute();
      attrs.setName("value");
      attrs.setFieldName(status.getName());
      attrs.setCondition(condition);
      attrs.setExpression("eval:" + getTyped(transition.getTarget().getSequence(), status));

      fields.add(attrs);
    }

    return fields;
  }

  private String getFilters(List<Filter> filterList) {

    String jsonField =
        wkfService.workflow.getIsJson() ? null : "$" + wkfService.workflow.getJsonField();
    if (jsonField != null && jsonField.equals("$attrs")) {
      jsonField = null;
    }
    String filters = filterGroovyService.getGroovyFilters(filterList, jsonField);
    log.debug("Filters : {}", filters);

    return filters;
  }

  private String getTyped(Integer value, MetaJsonField status) {

    String typeName = status.getType();
    if (typeName.equals("integer")) {
      return value.toString();
    }

    return "'" + value + "'";
  }

  /**
   * Create toolbar ViewButton in ViewBuilder from WkfTransition. Method called if WkfTransition is
   * based on button.
   *
   * @param viewBuilder ViewBuilder to update.
   * @param transition WkfTransition to process.
   * @param condition Condition to show button
   * @param sequence Sequence of button to add in toolbar.
   */
  private void addButton(WkfTransition transition, String condition, Integer sequence) {

    //    String source = transition.getSource().getName();
    String title = transition.getButtonTitle();
    //    String name = wkfService.inflector.camelize(source + "-" + title, true);
    // FIXME:Have to check if its working with import export of workflow.
    String name = "transition" + transition.getId();
    if (name.equals("save") || name.equals("cancel") || name.equals("back")) {
      name = "wkf" + name;
    }
    wkfButtonNames.add(name);

    MetaJsonField button = wkfService.getJsonField(name, "button");
    button.setTitle(title);
    ;
    button.setShowIf(condition);
    button.setSequence(sequence);
    button.setVisibleInGrid(false);
    button.setIsWkf(true);
    button.setWidgetAttrs("{\"colSpan\": \"3\"}");
    button.setOnClick(addButtonActions(transition, name));

    if (transition.getRoleSet() != null) {
      Set<Role> buttonRoles = new HashSet<>();
      buttonRoles.addAll(transition.getRoleSet());
      button.setRoles(buttonRoles);
    }

    log.debug("Adding button : {}", button.getName());
    wkfService.saveJsonField(button);

    //		String permName = this.wkfService.moduleName + "."
    //				+ wkfService.dasherizeModel.replace("-", ".") + name;
    //		clearOldMetaPermissions(permName);
    //		addButtonPermissions(permName, name, transition.getRoleSet());

  }

  /**
   * Method create action for button from WkfTransition and related destination nodes. It set
   * onClick of ViewButton with new action created.
   *
   * @param viewButton ViewButton to update for onClick.
   * @param transition WkfTransition from where action created.
   * @param buttonName Name of button used in creation of action name.
   */
  private String addButtonActions(WkfTransition transition, String buttonName) {

    String actionName = buttonName.toLowerCase().replace(" ", "-");
    actionName = "action-" + wkfService.wkfId + "-" + actionName;
    List<String> actions = new ArrayList<String>();
    String xml = "";
    Integer alterType = transition.getAlertTypeSelect();
    String alertMsg = transition.getAlertMsg();
    if (alterType > 0 && alertMsg != null) {
      String type = "alert";
      if (alterType == 2) {
        type = "info";
      }
      String alertAction = actionName + "-alert";
      xml = getActionValidateXML(alertAction, type, alertMsg, transition.getConditions());
      metaService.updateMetaAction(alertAction, "action-validate", xml, null);
      actions.add(alertAction);
    }

    List<Attribute> attrs = new ArrayList<Attribute>();
    Attribute attr = new Attribute();
    MetaJsonField wkfField = wkfService.workflow.getStatusField();
    attr.setName("value");
    attr.setFieldName(wkfField.getName());
    attr.setExpression("eval:" + getTyped(transition.getTarget().getSequence(), wkfField));
    attrs.add(attr);
    actions.add(actionName);
    xml = getActionXML(actionName, attrs);
    metaService.updateMetaAction(actionName, "action-record", xml, null);
    // actions.add("save");
    //		actions.add(WkfTrackingService.ACTION_TRACK);

    String successMsg = transition.getSuccessMsg();
    if (successMsg != null) {
      String sucessAction = actionName + "-success";
      xml = getActionValidateXML(sucessAction, "notify", successMsg, null);
      metaService.updateMetaAction(sucessAction, "action-validate", xml, null);
      actions.add(sucessAction);
    }
    actions.add("save");
    actions.add("action-group-" + wkfService.wkfId);
    actions.add("save");

    return Joiner.on(",").join(actions);
  }

  /**
   * Method create ActionRecord from given name and list of RecordField. It generate xml from
   * ActionRecord.
   *
   * @param name Name of action.
   * @param fields List of ActionRecord.
   * @return Xml of ActionRecord created.
   */
  private String getActionXML(String name, List<Attribute> attrs) {

    log.debug("Creating action attrs: {}", name);
    ActionAttrs action = new ActionAttrs();
    action.setName(name);
    action.setAttributes(attrs);

    return XMLViews.toXml(action, true);
  }

  /**
   * Method create ActionValidate from given name,type and validation message.
   *
   * @param name Name of action
   * @param type Type of validate action ('notify','info' or 'alert').
   * @param message Message to display for action.
   * @return Xml generated from ActionValidate.
   */
  private String getActionValidateXML(
      String name, String type, String message, List<Filter> conditions) {

    ActionValidate actionValidate = new ActionValidate();
    actionValidate.setName(name);
    List<Validator> validators = new ArrayList<ActionValidate.Validator>();
    String condition = getFilters(conditions);
    switch (type) {
      case "notify":
        Notify notify = new Notify();
        notify.setMessage(message);
        notify.setCondition(condition);
        validators.add(notify);
        break;
      case "info":
        Info info = new Info();
        info.setMessage(message);
        info.setCondition(condition);
        validators.add(info);
        break;
      default:
        Alert alert = new Alert();
        alert.setMessage(message);
        alert.setCondition(condition);
        validators.add(alert);
        break;
    }

    actionValidate.setValidators(validators);

    return XMLViews.toXml(actionValidate, true);
  }

  /**
   * Create/Update MetaPermission for button and set this permission in roles.
   *
   * @param name Name of permission to create/update.
   * @param buttonName Name of button to add permission.
   * @param roles Roles to update with permission.
   */
  @Transactional
  public void addButtonPermissions(String name, String buttonName, Set<Role> roles) {

    if (roles == null || roles.isEmpty()) {
      return;
    }

    MetaPermission permission = metaPermissionRepo.findByName(name);
    if (permission == null) {
      permission = new MetaPermission(name);
      permission.setObject(wkfService.workflow.getModel());
      MetaPermissionRule rule = new MetaPermissionRule();
      rule.setCanRead(false);
      rule.setField(buttonName);
      permission.addRule(rule);
      permission = metaPermissionRepo.save(permission);
    }

    for (Role role : roles) {
      role.addMetaPermission(permission);
      wkfService.roleRepo.save(role);
    }
  }

  /**
   * Clear old button permission from all roles having it.
   *
   * @param name Name of permission to clear.
   */
  @Transactional
  public void clearOldMetaPermissions(String name) {

    MetaPermission permission = metaPermissionRepo.findByName(name);

    if (permission != null) {
      List<Role> oldRoleList =
          wkfService
              .roleRepo
              .all()
              .filter("self.metaPermissions.id = ?1", permission.getId())
              .fetch();
      for (Role role : oldRoleList) {
        role.removeMetaPermission(permission);
        wkfService.roleRepo.save(role);
      }
    }
  }
}
