/*
 * Copyright 1998-2010 Linux.org.ru
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package ru.org.linux.spring;

import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import ru.org.linux.site.*;
import ru.org.linux.util.HTMLFormatter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.context.support.ApplicationObjectSupport;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class UserModificationController extends ApplicationObjectSupport {
  private SearchQueueSender searchQueueSender;

  @Autowired
  @Required
  public void setSearchQueueSender(SearchQueueSender searchQueueSender) {
    this.searchQueueSender = searchQueueSender;
  }

  @RequestMapping(value="/usermod.jsp", method= RequestMethod.POST)
  public ModelAndView modifyUser(
    HttpServletRequest request,
    HttpSession session,
    @RequestParam("action") String action,
    @RequestParam("id") int id,
    @RequestParam(value="reason", required = false) String reason
  ) throws Exception {
    Template tmpl = Template.getTemplate(request);

    if (!tmpl.isModeratorSession()) {
      throw new AccessViolationException("Not moderator");
    }

    Connection db = null;

    try {
      db = LorDataSource.getConnection();
      db.setAutoCommit(false);

      Statement st = db.createStatement();

      User user = User.getUser(db, id);

      User moderator = User.getUser(db, tmpl.getNick());

      if ("block".equals(action) || "block-n-delete-comments".equals(action)) {
        if (!user.isBlockable() && !moderator.isAdministrator()) {
          throw new AccessViolationException("Пользователя " + user.getNick() + " нельзя заблокировать");
        }

        user.block(db, moderator, reason);
        user.resetPassword(db);
        logger.info("User " + user.getNick() + " blocked by " + session.getValue("nick"));

        if ("block-n-delete-comments".equals(action)) {
          Map<String, Object> params = new HashMap<String, Object>();
          params.put("message", "Удалено");
          List<Integer> deleted = user.deleteAllComments(db, moderator);
          params.put("bigMessage", deleted);
          db.commit();
          
          searchQueueSender.updateComment(deleted);
          return new ModelAndView("action-done", params);
        }
      } else if ("toggle_corrector".equals(action)) {
        if (user.getScore()<User.CORRECTOR_SCORE) {
          throw new AccessViolationException("Пользователя " + user.getNick() + " нельзя сделать корректором");
        }

        if (user.canCorrect()) {
          st.executeUpdate("UPDATE users SET corrector='f' WHERE id=" + id);
        } else {
          st.executeUpdate("UPDATE users SET corrector='t' WHERE id=" + id);
        }
      } else if ("unblock".equals(action)) {
        if (!user.isBlockable() && !moderator.isAdministrator()) {
          throw new AccessViolationException("Пользователя " + user.getNick() + " нельзя разблокировать");
        }

        st.executeUpdate("UPDATE users SET blocked='f' WHERE id=" + id);
        st.executeUpdate("DELETE FROM ban_info WHERE userid="+id);
        logger.info("User " + user.getNick() + " unblocked by " + session.getValue("nick"));
      } else if ("remove_userinfo".equals(action)) {
        if (user.canModerate()) {
          throw new AccessViolationException("Пользователю " + user.getNick() + " нельзя удалить сведения");
        }

        user.setUserinfo(db, null);
        user.changeScore(db, -10);
        logger.info("Clearing " + user.getNick() + " userinfo");
      } else {
        throw new UserErrorException("Invalid action=" + HTMLFormatter.htmlSpecialChars(action));
      }

      db.commit();

      Random random = new Random();

      return new ModelAndView(new RedirectView("/people/" + URLEncoder.encode(user.getNick()) + "/profile?nocache=" + random.nextInt()));
    } finally {
      if (db != null) {
        db.close();
      }
    }
  }

  @RequestMapping(value="/remove-userpic.jsp", method= RequestMethod.POST)
  public ModelAndView removeUserpic(
    HttpServletRequest request,
    @RequestParam("id") int id
  ) throws Exception {
    Template tmpl = Template.getTemplate(request);

    if (!tmpl.isSessionAuthorized()) {
      throw new AccessViolationException("Not autorized");
    }

    Connection db = null;

    try {
      db = LorDataSource.getConnection();
      db.setAutoCommit(false);

      Statement st = db.createStatement();

      User user = User.getUser(db, id);

      User currentUser = User.getUser(db, tmpl.getNick());

      if (!currentUser.canModerate() && currentUser.getId()!=user.getId()) {
        throw new AccessViolationException("Not permitted");
      }

      if (user.canModerate()) {
        throw new AccessViolationException("Пользователю " + user.getNick() + " нельзя удалить картинку");
      }

      if (user.getPhoto() == null) {
        throw new AccessViolationException("Пользователь " + user.getNick() + " картинки не имеет");
      }

      st.executeUpdate("UPDATE users SET photo=null WHERE id=" + id);

      if (currentUser.canModerate() && currentUser.getId()!=user.getId()) {
        user.changeScore(db, -10);
      }

      logger.info("Clearing " + user.getNick() + " userpic by " + currentUser.getNick());

      db.commit();

      Random random = new Random();

      return new ModelAndView(new RedirectView("/people/" + URLEncoder.encode(user.getNick()) + "/profile?nocache=" + random.nextInt()));
    } finally {
      if (db != null) {
        db.close();
      }
    }
  }
  @RequestMapping(value="/karmaplus", method= RequestMethod.POST)
  public ModelAndView karmaPlus(
    HttpServletRequest request,
    @RequestParam("id") int id
  ) throws Exception {
    return karma(request, id, 1);
  }

  @RequestMapping(value="/karmaminus", method= RequestMethod.POST)
  public ModelAndView karmaMinus(
    HttpServletRequest request,
    @RequestParam("id") int id
  ) throws Exception {
    return karma(request, id, -1);
  }

  public ModelAndView karma(
    HttpServletRequest request,
    @RequestParam("id") int id,
    int value
  ) throws Exception {
    Template tmpl = Template.getTemplate(request);

    Connection db = LorDataSource.getConnection();

    try {
      db.setAutoCommit(false);

      if (tmpl.getKarmaVotes().contains(id)) {
        return new ModelAndView("login-xml", Collections.singletonMap("error", "нельзя повторно голосовать"));
      }

      User currentUser = tmpl.getCurrentUser();

      if (id==currentUser.getId()) {
        return new ModelAndView("login-xml", Collections.singletonMap("error", "нельзя голосовать за себя"));
      }

      if (currentUser.getKarmaVotes()<=0) {
        return new ModelAndView("login-xml", Collections.singletonMap("error", "голоса закончились"));
      }

      Statement st = db.createStatement();

      st.executeUpdate("INSERT INTO karma_voted VALUES ("+currentUser.getId()+","+id+")");

      if (value>0) {
        st.executeUpdate("UPDATE users SET karma=karma+1 WHERE id="+id);
      } else {
        st.executeUpdate("UPDATE users SET karma=karma-1 WHERE id="+id);
      }

      st.executeUpdate("UPDATE users SET karma_votes=karma_votes-1 WHERE id="+currentUser.getId());

      User user = User.getUser(db, id);

      db.commit();

      return new ModelAndView("login-xml", Collections.singletonMap("ok", Integer.toString(user.getKarma())));
    } finally {
      JdbcUtils.closeConnection(db);
    }
  }
}
