package com.ljj.tcc.core.interceptor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.ljj.tcc.api.TransactionPhase;
import com.ljj.tcc.core.NoExistedTransactionException;
import com.ljj.tcc.core.SystemException;
import com.ljj.tcc.core.Transaction;
import com.ljj.tcc.core.TransactionManager;
import com.ljj.tcc.core.utils.ReflectionUtils;
import com.ljj.tcc.core.utils.TransactionUtils;

/**
 * TCC事务服务拦截
 * 
 * Version 1.0.0
 * 
 * @author liangjinjing
 * 
 * Date 2019-05-24 11:34
 * 
 */
public class CompensableTransactionInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(CompensableTransactionInterceptor.class);

    /*
     * 延迟回滚的异常
     */
    private Set<Class<? extends Exception>> delayCancelExceptions = new HashSet<Class<? extends Exception>>();

    /*
     * 事务管理器
     */
    private TransactionManager transactionManager;
    
    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public void setDelayCancelExceptions(Set<Class<? extends Exception>> delayCancelExceptions) {
        this.delayCancelExceptions.addAll(delayCancelExceptions);
    }

    /**
     * 拦截TCC服务的方法
     * @param pjp
     * @return
     * @throws Throwable
     */
    public Object interceptCompensableMethod(ProceedingJoinPoint pjp) throws Throwable {
        CompensableMethodContext compensableMethodContext = new CompensableMethodContext(pjp);
        boolean isTransactionActive = transactionManager.isTransactionActive();
        if (!TransactionUtils.isLegalTransactionContext(isTransactionActive, compensableMethodContext)) {
            throw new SystemException("no active compensable transaction while propagation is mandatory for method " + compensableMethodContext.getMethod().getName());
        }
        switch (compensableMethodContext.getMethodRole(isTransactionActive)) {
            case ROOT:
                return rootMethodProceed(compensableMethodContext);
            case PROVIDER:
                return providerMethodProceed(compensableMethodContext);
            default:
                return pjp.proceed();
        }
    }


    /**
     * 
     * @param compensableMethodContext
     * @return
     * @throws Throwable
     */
    private Object rootMethodProceed(CompensableMethodContext compensableMethodContext) throws Throwable {
        Object returnValue = null;
        Transaction transaction = null;
        boolean asyncConfirm = compensableMethodContext.getAnnotation().asyncConfirm();
        boolean asyncCancel = compensableMethodContext.getAnnotation().asyncCancel();
        Set<Class<? extends Exception>> allDelayCancelExceptions = new HashSet<Class<? extends Exception>>();
        allDelayCancelExceptions.addAll(this.delayCancelExceptions);
        allDelayCancelExceptions.addAll(Arrays.asList(compensableMethodContext.getAnnotation().delayCancelExceptions()));
        try {
            transaction = transactionManager.begin(compensableMethodContext.getUniqueIdentity());
            try {
                returnValue = compensableMethodContext.proceed();
            } catch (Throwable tryingException) {
                if (!isDelayCancelException(tryingException, allDelayCancelExceptions)) {
                    logger.warn(String.format("compensable transaction trying failed. transaction content:%s", JSON.toJSONString(transaction)), tryingException);
                    transactionManager.rollback(asyncCancel);
                }
                throw tryingException;
            }
            transactionManager.commit(asyncConfirm);
        } finally {
            transactionManager.cleanAfterCompletion(transaction);
        }
        return returnValue;
    }

    /**
     * 
     * @param compensableMethodContext
     * @return
     * @throws Throwable
     */
    private Object providerMethodProceed(CompensableMethodContext compensableMethodContext) throws Throwable {
        Transaction transaction = null;
        boolean asyncConfirm = compensableMethodContext.getAnnotation().asyncConfirm();
        boolean asyncCancel = compensableMethodContext.getAnnotation().asyncCancel();
        try {
            switch (TransactionPhase.valueOf(compensableMethodContext.getTransactionContext().getPhase())) {
                case TRYING:
                    transaction = transactionManager.propagationNewBegin(compensableMethodContext.getTransactionContext());
                    return compensableMethodContext.proceed();
                case CONFIRMING:
                    try {
                        transaction = transactionManager.propagationExistBegin(compensableMethodContext.getTransactionContext());
                        transactionManager.commit(asyncConfirm);
                    } catch (NoExistedTransactionException excepton) {
                        //the transaction has been commit,ignore it.
                    }
                    break;
                case CANCELLING:
                    try {
                        transaction = transactionManager.propagationExistBegin(compensableMethodContext.getTransactionContext());
                        transactionManager.rollback(asyncCancel);
                    } catch (NoExistedTransactionException exception) {
                        //the transaction has been rollback,ignore it.
                    }
                    break;
            }

        } finally {
            transactionManager.cleanAfterCompletion(transaction);
        }
        Method method = compensableMethodContext.getMethod();
        return ReflectionUtils.getNullValue(method.getReturnType());
    }

    /**
     * 
     * @param throwable
     * @param delayCancelExceptions
     * @return
     */
    private boolean isDelayCancelException(Throwable throwable, Set<Class<? extends Exception>> delayCancelExceptions) {
        if (delayCancelExceptions != null) {
            for (Class<?> delayCancelException : delayCancelExceptions) {
                Throwable rootCause = ExceptionUtils.getRootCause(throwable);
                if (delayCancelException.isAssignableFrom(throwable.getClass())
                        || (rootCause != null && delayCancelException.isAssignableFrom(rootCause.getClass()))) {
                    return true;
                }
            }
        }
        return false;
    }

}
